package kz.tungo.sdk.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kz.tungo.sdk.ApiResponse;
import kz.tungo.sdk.RequestOptions;
import kz.tungo.sdk.SdkVersion;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.TungoException;
import kz.tungo.sdk.errors.TungoTimeoutException;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Sends requests: authentication, headers, timeouts, retries, error mapping, decoding and
 * streaming. One engine is shared by a sync client and its async view. Internal: not part of the
 * public API.
 */
public final class HttpEngine implements AutoCloseable {
    private static final AtomicInteger IDS = new AtomicInteger();

    private final ClientConfig config;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final ExecutorService httpExecutor;
    private final Executor asyncExecutor;
    private final ExecutorService ownedAsyncExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an engine.
     *
     * @param config the resolved client configuration
     */
    public HttpEngine(ClientConfig config) {
        this.config = config;
        int id = IDS.incrementAndGet();
        if (config.httpClient != null) {
            this.http = config.httpClient;
            this.ownsHttp = false;
            this.httpExecutor = null;
        } else {
            this.httpExecutor = Executors.newCachedThreadPool(daemonFactory("tungo-sdk-http-" + id));
            this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(httpExecutor)
                .build();
            this.ownsHttp = true;
        }
        if (config.asyncExecutor != null) {
            this.asyncExecutor = config.asyncExecutor;
            this.ownedAsyncExecutor = null;
        } else {
            // The async client runs the blocking call on this pool. It is bounded: at most
            // asyncMaxThreads calls run at once, the rest wait in the queue; idle threads end.
            ThreadPoolExecutor pool = new ThreadPoolExecutor(config.asyncMaxThreads, config.asyncMaxThreads,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), daemonFactory("tungo-sdk-async-" + id));
            pool.allowCoreThreadTimeOut(true);
            this.ownedAsyncExecutor = pool;
            this.asyncExecutor = pool;
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Returns the configuration of the client.
     *
     * @return the configuration
     */
    public ClientConfig config() {
        return config;
    }

    /**
     * Sends a request and decodes the JSON body of the success response.
     *
     * @param spec the request
     * @param options per-request options, may be null
     * @param type the expected body type
     * @param <T> the expected body type
     * @return the decoded response
     */
    public <T> ApiResponse<T> execute(RequestSpec spec, RequestOptions options, JavaType type) {
        Exchange x = run(spec, options, false);
        String requestId = x.headers.firstValue("x-request-id").orElse(null);
        if (x.body.length == 0 || new String(x.body, StandardCharsets.UTF_8).trim().isEmpty()) {
            throw new DecodeException("The server returned HTTP " + x.status + " with an empty body where JSON was expected",
                null, x.status, requestId, "");
        }
        try {
            T value = Json.decode(x.body, type);
            if (value == null) {
                throw new DecodeException("The server returned HTTP " + x.status + " with a JSON null body",
                    null, x.status, requestId, "null");
            }
            return new ApiResponse<>(x.status, x.headers, value);
        } catch (IOException e) {
            throw new DecodeException("The server returned HTTP " + x.status + " with a body that cannot be decoded: "
                + Json.shortMessage(e), e, x.status, requestId, new String(x.body, StandardCharsets.UTF_8));
        }
    }

    /**
     * Sends a request and decodes the JSON body of the success response.
     *
     * @param spec the request
     * @param options per-request options, may be null
     * @param type the expected body class
     * @param <T> the expected body type
     * @return the decoded response
     */
    public <T> ApiResponse<T> execute(RequestSpec spec, RequestOptions options, Class<T> type) {
        return execute(spec, options, Json.type(type));
    }

    /**
     * Sends a request whose success response has no body.
     *
     * @param spec the request
     * @param options per-request options, may be null
     * @return the response without a body
     */
    public ApiResponse<Void> executeEmpty(RequestSpec spec, RequestOptions options) {
        Exchange x = run(spec, options, false);
        return new ApiResponse<>(x.status, x.headers, null);
    }

    /**
     * Opens a server-sent event stream. The request is retried only until the response headers
     * arrive; after that the caller owns the connection.
     *
     * @param spec the request
     * @param options per-request options, may be null
     * @return the open connection
     */
    public SseConnection openStream(RequestSpec spec, RequestOptions options) {
        RequestOptions opts = options == null ? RequestOptions.none() : options;
        Exchange x = run(spec, opts, true);
        String contentType = x.headers.firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        long idleNanos = opts.streamIdleTimeout().orElse(config.streamIdleTimeout).toNanos();
        if (isReplayedJson(x.headers)) {
            // A chat turn repeated with the same Idempotency-Key after it finished: the server
            // answers the stored ChatTurnResult instead of a stream. The caller gets its events.
            byte[] replayed;
            try {
                replayed = readBody(x.stream, System.nanoTime() + idleNanos);
            } catch (BodyTimeoutException e) {
                throw new TungoTimeoutException("The replayed response was not received in time (" + spec.method + " "
                    + spec.route + ")", e);
            } catch (IOException e) {
                throw new ConnectionException("Connection error (" + spec.method + " " + spec.route + "): " + describe(e), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedRequestException("Interrupted while reading the response", e);
            }
            String requestId = x.headers.firstValue("x-request-id").filter(s -> !s.isEmpty()).orElse(null);
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(x.headers.map());
            byte[] events = replayAsEventStream(replayed, requestId, headers);
            if (events == null) {
                throw new DecodeException("The replayed response is not a chat turn result (HTTP " + x.status + ")", null,
                    x.status, requestId, new String(replayed, StandardCharsets.UTF_8));
            }
            return new SseConnection(new ApiResponse<>(x.status, HttpHeaders.of(headers, (name, value) -> true), null),
                ResponseBody.of(events), idleNanos);
        }
        if (!isEventStream(contentType)) {
            // A success reply that is not an event stream (JSON, HTML of a proxy, no type at all)
            // must not look like a stream that ended without events.
            String text;
            try {
                text = new String(readBody(x.stream, System.nanoTime() + idleNanos), StandardCharsets.UTF_8);
            } catch (IOException | BodyTimeoutException e) {
                text = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedRequestException("Interrupted while reading the response", e);
            }
            throw new DecodeException("Expected a text/event-stream response but the server returned HTTP " + x.status
                + " with " + (contentType.isEmpty() ? "no Content-Type" : contentType), null, x.status,
                x.headers.firstValue("x-request-id").orElse(null), text);
        }
        return new SseConnection(new ApiResponse<>(x.status, x.headers, null), x.stream, idleNanos);
    }

    /** Whether a stream call was answered with a stored result replayed by Idempotency-Key. */
    private static boolean isReplayedJson(HttpHeaders headers) {
        String contentType = headers.firstValue("content-type").orElse("");
        int semicolon = contentType.indexOf(';');
        String media = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return media.equalsIgnoreCase("application/json")
            && headers.firstValue("idempotent-replayed").orElse("").trim().equalsIgnoreCase("true");
    }

    /**
     * Turns a replayed ChatTurnResult into the event stream of the turn it stands for: text (the
     * answer), proposal, question, citation, error and choice events, then done. The dialog id and
     * the turn number, which a replay carries only in the body, are added to {@code headers} as
     * X-Dialog-Id and X-Turn-No when they are missing.
     *
     * @return the event stream, or null when the body is not a chat turn result
     */
    private static byte[] replayAsEventStream(byte[] body, String requestId, Map<String, List<String>> headers) {
        JsonNode parsed;
        try {
            parsed = Json.MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
        if (parsed == null || !parsed.isObject() || !parsed.path("dialog_id").isTextual()) {
            return null;
        }
        ObjectNode result = (ObjectNode) parsed;
        if (headers.getOrDefault("x-dialog-id", List.of()).stream().allMatch(String::isEmpty)) {
            headers.put("x-dialog-id", List.of(result.get("dialog_id").asText()));
        }
        JsonNode turnNo = result.path("turn_no");
        if (turnNo.canConvertToExactIntegral() && headers.getOrDefault("x-turn-no", List.of()).stream().allMatch(String::isEmpty)) {
            headers.put("x-turn-no", List.of(turnNo.bigIntegerValue().toString()));
        }
        StringBuilder out = new StringBuilder();
        String answer = result.path("answer").isTextual() ? result.get("answer").asText() : "";
        if (!answer.isEmpty()) {
            ObjectNode text = Json.MAPPER.createObjectNode();
            text.put("delta", answer);
            emit(out, "text", text);
        }
        for (ObjectNode proposal : entries(result, "proposals")) {
            JsonNode id = proposal.remove("id");
            proposal.set("proposal_id", id == null ? NullNode.getInstance() : id);
            emit(out, "proposal", proposal);
        }
        for (ObjectNode question : entries(result, "questions")) {
            emit(out, "question", question);
        }
        for (ObjectNode citation : entries(result, "citations")) {
            emit(out, "citation", citation);
        }
        for (ObjectNode error : entries(result, "errors")) {
            ObjectNode event = Json.MAPPER.createObjectNode();
            event.set("error", error);
            event.put("request_id", requestId);
            emit(out, "error", event);
        }
        ObjectNode done = Json.MAPPER.createObjectNode();
        done.put("answer", answer);
        if (result.path("stop_reason").isTextual()) {
            done.set("stop_reason", result.get("stop_reason"));
        }
        if (result.path("usage").isObject()) {
            ObjectNode usage = ((ObjectNode) result.get("usage")).deepCopy();
            JsonNode cost = usage.remove("cost_tiyn");
            done.set("usage", usage);
            if (cost != null) {
                done.set("cost_tiyn", cost);
            }
        }
        if (result.path("pending_question").isObject()) {
            ObjectNode pending = ((ObjectNode) result.get("pending_question")).deepCopy();
            JsonNode choiceId = pending.get("choice_id");
            emit(out, "choice", pending);
            if (choiceId != null) {
                done.set("choice_id", choiceId);
            }
        }
        emit(out, "done", done);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<ObjectNode> entries(ObjectNode result, String key) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode item : result.path(key)) {
            if (item.isObject()) {
                out.add(((ObjectNode) item).deepCopy());
            }
        }
        return out;
    }

    private static void emit(StringBuilder out, String type, ObjectNode event) {
        event.put("type", type);
        String data;
        try {
            data = Json.MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a JSON tree always serialises", e);
        }
        out.append("event: ").append(type).append("\ndata: ").append(data).append("\n\n");
    }

    private static boolean isEventStream(String contentType) {
        int semicolon = contentType.indexOf(';');
        String media = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return media.equals("text/event-stream");
    }

    /**
     * Runs a blocking call on the async executor.
     *
     * @param call the blocking call
     * @param <T> the result type
     * @return a future completed with the result or the exception of the call; cancelling it
     *     interrupts the call
     */
    public <T> CompletableFuture<T> async(Supplier<T> call) {
        ensureOpen();
        InterruptingFuture<T> future = new InterruptingFuture<>();
        AsyncTask task = new AsyncTask(future, () -> {
            try {
                future.complete(call.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return null;
        });
        future.task = task;
        try {
            asyncExecutor.execute(task);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * The task of one async call. When it is cancelled before its body completed the future
     * (close() drains the queued calls of the owned pool), the future fails instead of never
     * completing; a future cancelled by the caller stays cancelled.
     */
    private static final class AsyncTask extends FutureTask<Void> {
        private final CompletableFuture<?> future;

        AsyncTask(CompletableFuture<?> future, Callable<Void> body) {
            super(body);
            this.future = future;
        }

        @Override
        protected void done() {
            if (isCancelled()) {
                future.completeExceptionally(new IllegalStateException("The client is closed"));
            }
        }
    }

    private static final class InterruptingFuture<T> extends CompletableFuture<T> {
        private volatile FutureTask<Void> task;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            FutureTask<Void> t = task;
            if (cancelled && t != null) {
                t.cancel(true);
            }
            return cancelled;
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("The client is closed");
        }
    }

    private static final class Exchange {
        final int status;
        final HttpHeaders headers;
        final byte[] body;
        final ResponseBody stream;

        Exchange(int status, HttpHeaders headers, byte[] body, ResponseBody stream) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.stream = stream;
        }
    }

    private static final class BodyTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;

        BodyTimeoutException() {
            super("timed out while reading the response body");
        }
    }

    private Exchange run(RequestSpec spec, RequestOptions options, boolean streaming) {
        ensureOpen();
        RequestOptions opts = options == null ? RequestOptions.none() : options;
        boolean keyed = spec.auth == RequestSpec.Auth.KEY;
        if (keyed && (config.apiKey == null || config.apiKey.isEmpty())) {
            throw new ConfigException("No API key is configured: pass apiKey(...) to the client builder or set the "
                + "TUNGO_API_KEY environment variable");
        }
        // A key goes only to an operation that declares the header: anywhere else the API ignores
        // it, and sending it would make a retry look safe when it repeats the action.
        String idempotencyKey = spec.idempotencyHeader != null ? opts.idempotencyKey().orElse(null) : null;
        String idempotencyHeader = spec.idempotencyHeader != null ? spec.idempotencyHeader : "Idempotency-Key";
        if (idempotencyKey == null && spec.idempotencyHeader != null) {
            idempotencyKey = spec.defaultIdempotencyKey;
        }
        if (idempotencyKey == null && spec.idempotencyHeader != null) {
            idempotencyKey = UUID.randomUUID().toString();
        }
        boolean retrySafe = RetryPolicy.isRetrySafe(spec.method, idempotencyKey != null);
        int maxRetries = opts.maxRetries().orElse(config.maxRetries);
        Duration timeout;
        if (streaming) {
            timeout = opts.streamIdleTimeout().orElse(config.streamIdleTimeout);
        } else if (opts.timeout().isPresent()) {
            timeout = opts.timeout().get();
        } else if (config.timeout != null) {
            timeout = config.timeout;
        } else {
            timeout = spec.longRunning ? ClientConfig.DEFAULT_LONG_TIMEOUT : ClientConfig.DEFAULT_TIMEOUT;
        }
        URI uri;
        try {
            uri = URI.create(config.baseUrl + spec.path + (spec.query.isEmpty() ? "" : "?" + spec.query));
        } catch (IllegalArgumentException e) {
            // The message of the cause quotes the whole URL, which may hold a secret path or query.
            throw new ValidationException("The request URL is not valid (" + spec.method + " " + spec.route + ")", null);
        }

        for (int attempt = 0;; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedRequestException("Interrupted before the request was sent", null);
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            HttpRequest request = buildRequest(spec, opts, uri, keyed, idempotencyHeader, idempotencyKey, streaming, timeout);
            TungoException failure;
            Double serverDelay = null;
            try {
                HttpResponse<ResponseBody> response = http.send(request, info -> new ResponseBody());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    if (streaming) {
                        return new Exchange(status, response.headers(), null, response.body());
                    }
                    return new Exchange(status, response.headers(), readBody(response.body(), deadline), null);
                }
                String errorBody = new String(readBody(response.body(), deadline), StandardCharsets.UTF_8);
                ApiException error = ApiException.from(status, response.headers(), errorBody, config.clock);
                if (!RetryPolicy.isRetryableStatus(status)) {
                    throw error;
                }
                serverDelay = error.retryAfterSeconds().orElse(null);
                if (serverDelay != null && serverDelay > RetryPolicy.MAX_RETRY_AFTER_SECONDS) {
                    throw error;
                }
                failure = error;
            } catch (HttpTimeoutException | BodyTimeoutException e) {
                failure = new TungoTimeoutException("The request timed out after " + timeout.toMillis() + " ms ("
                    + spec.method + " " + spec.route + ")", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedRequestException("Interrupted while waiting for the response", e);
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedRequestException("Interrupted while reading the response", e);
                }
                failure = new ConnectionException("Connection error (" + spec.method + " " + spec.route + "): " + describe(e), e);
            } catch (IllegalArgumentException e) {
                // The JDK message can quote a header value (the API key among them): never pass it on.
                throw new ValidationException(UNBUILDABLE);
            }
            if (!retrySafe || attempt >= maxRetries) {
                throw failure;
            }
            sleep(RetryPolicy.delaySeconds(serverDelay, attempt), failure);
        }
    }

    private HttpRequest buildRequest(RequestSpec spec, RequestOptions opts, URI uri, boolean keyed, String idempotencyHeader,
        String idempotencyKey, boolean streaming, Duration timeout) {
        HttpRequest.Builder rb;
        try {
            rb = HttpRequest.newBuilder(uri);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("The base URL must be an absolute http or https URL: " + config.baseUrl, e);
        }
        try {
            rb.timeout(timeout);
            rb.setHeader("Accept", streaming ? "text/event-stream" : "application/json");
            rb.setHeader("User-Agent", SdkVersion.USER_AGENT);
            boolean dropKey = spec.idempotencyHeader == null;
            for (Map.Entry<String, String> h : config.defaultHeaders.entrySet()) {
                if ((!keyed && isCredentialHeader(h.getKey()))
                    || (dropKey && "Idempotency-Key".equalsIgnoreCase(h.getKey()))) {
                    continue;
                }
                setHeader(rb, h.getKey(), h.getValue());
            }
            if (keyed) {
                setHeader(rb, "x-api-key", config.apiKey);
            }
            if (spec.body != null && spec.contentType != null) {
                rb.setHeader("Content-Type", spec.contentType);
            }
            if (idempotencyKey != null) {
                setHeader(rb, idempotencyHeader, idempotencyKey);
            }
            for (Map.Entry<String, String> h : spec.headers.entrySet()) {
                setHeader(rb, h.getKey(), h.getValue());
            }
            for (Map.Entry<String, String> h : opts.headers().entrySet()) {
                if (dropKey && "Idempotency-Key".equalsIgnoreCase(h.getKey())) {
                    continue;
                }
                setHeader(rb, h.getKey(), h.getValue());
            }
            rb.method(spec.method, spec.body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(spec.body));
            return rb.build();
        } catch (IllegalArgumentException e) {
            // The JDK message quotes the rejected header value, which can be the API key: neither the
            // message nor the exception itself (as the cause) is passed on.
            throw new ValidationException(UNBUILDABLE);
        }
    }

    private static final String UNBUILDABLE = "The request could not be built: a header name or value is invalid";

    /**
     * Sets one header after checking its value, so that a rejected value is named by its header
     * and never quoted.
     */
    private static void setHeader(HttpRequest.Builder rb, String name, String value) {
        if (!isHeaderValueSafe(value)) {
            throw new ValidationException("The request could not be built: the value of header '" + name
                + "' contains a control character (such as a trailing newline)");
        }
        rb.setHeader(name, value);
    }

    /**
     * Tells whether a header value can be sent: no control character but the tab.
     * Internal: not part of the public API.
     *
     * @param value the header value
     * @return true when the value has no CR, LF, NUL or other control character
     */
    public static boolean isHeaderValueSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCredentialHeader(String name) {
        return name.equalsIgnoreCase("x-api-key") || name.equalsIgnoreCase("authorization");
    }

    private static byte[] readBody(ResponseBody in, long deadlineNanos)
        throws IOException, BodyTimeoutException, InterruptedException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer, deadlineNanos)) >= 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (TimeoutException e) {
            BodyTimeoutException timeout = new BodyTimeoutException();
            timeout.initCause(e);
            throw timeout;
        } finally {
            in.close();
        }
    }

    private static void sleep(double seconds, TungoException pending) {
        long millis = (long) Math.ceil(seconds * 1000.0);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedRequestException interrupted =
                new InterruptedRequestException("Interrupted while waiting to retry the request", e);
            interrupted.addSuppressed(pending);
            throw interrupted;
        }
    }

    /**
     * Describes an I/O failure in one line; never includes request headers.
     *
     * @param e the failure
     * @return the class name and the message of the failure
     */
    static String describe(Throwable e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        if ((message == null || message.isEmpty()) && cause != null && cause != e) {
            return e.getClass().getSimpleName() + " caused by " + describe(cause);
        }
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Releases the resources the engine created: its executors and its HTTP client.
     * A caller-supplied HTTP client or executor is left untouched.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownedAsyncExecutor != null) {
            // The queued calls never run: fail their futures, so join()/get() and dependent
            // stages do not wait forever. The running calls are interrupted and fail on their own.
            for (Runnable queued : ownedAsyncExecutor.shutdownNow()) {
                if (queued instanceof FutureTask) {
                    ((FutureTask<?>) queued).cancel(false);
                }
            }
        }
        if (ownsHttp) {
            try {
                // HttpClient.shutdownNow() exists since Java 21.
                Method shutdownNow = HttpClient.class.getMethod("shutdownNow");
                shutdownNow.invoke(http);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Java 11-20: the client releases its threads when it is garbage collected
            }
            if (httpExecutor != null) {
                httpExecutor.shutdownNow();
            }
        }
    }
}
