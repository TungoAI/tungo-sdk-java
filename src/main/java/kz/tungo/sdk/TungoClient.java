package kz.tungo.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.internal.ClientConfig;
import kz.tungo.sdk.internal.HttpEngine;
import kz.tungo.sdk.internal.Polling;
import kz.tungo.sdk.models.AutomationHookAccepted;
import kz.tungo.sdk.models.AutomationManualRun;
import kz.tungo.sdk.models.AutomationRunDetail;
import kz.tungo.sdk.models.AutomationScenarioRunInput;
import kz.tungo.sdk.models.BatchJob;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.ChatTurnResult;
import kz.tungo.sdk.models.WebhookEvent;
import kz.tungo.sdk.resources.WorkspacesResource;
import kz.tungo.sdk.resources.CompaniesResource;
import kz.tungo.sdk.resources.PlatformResource;
import kz.tungo.sdk.resources.ApiKeysResource;
import kz.tungo.sdk.resources.WebhooksResource;
import kz.tungo.sdk.resources.ChatResource;
import kz.tungo.sdk.resources.ProposalsResource;
import kz.tungo.sdk.resources.FeedResource;
import kz.tungo.sdk.resources.BatchesResource;
import kz.tungo.sdk.resources.EsfResource;
import kz.tungo.sdk.resources.FormsResource;
import kz.tungo.sdk.resources.OnecResource;
import kz.tungo.sdk.resources.AutomationsResource;

/**
 * The synchronous client of the Tungo Platform API.
 *
 * <p>Create one client and share it: it is immutable and safe to use from several threads.
 * Every method blocks the calling thread; interrupting the thread aborts the request, the retry
 * delay or the stream read with {@link kz.tungo.sdk.errors.InterruptedRequestException} and
 * leaves the interrupt flag set. {@link #async()} returns a view of the same client whose methods
 * return {@link java.util.concurrent.CompletableFuture}.
 *
 * <pre>{@code
 * try (TungoClient client = TungoClient.builder().apiKey("tg_...").build()) {
 *     client.companies().list().data().forEach(c -> System.out.println(c.id()));
 * }
 * }</pre>
 */
public final class TungoClient implements AutoCloseable {
    private final HttpEngine engine;
    private final TungoAsyncClient async;
    private final WorkspacesResource workspaces;
    private final CompaniesResource companies;
    private final PlatformResource platform;
    private final ApiKeysResource apiKeys;
    private final WebhooksResource webhooks;
    private final ChatResource chat;
    private final ProposalsResource proposals;
    private final FeedResource feed;
    private final BatchesResource batches;
    private final EsfResource esf;
    private final FormsResource forms;
    private final OnecResource onec;
    private final AutomationsResource automations;

    private TungoClient(ClientConfig config) {
        this.engine = new HttpEngine(config);
        this.workspaces = new WorkspacesResource(engine);
        this.companies = new CompaniesResource(engine);
        this.platform = new PlatformResource(engine);
        this.apiKeys = new ApiKeysResource(engine);
        this.webhooks = new WebhooksResource(engine);
        this.chat = new ChatResource(engine);
        this.proposals = new ProposalsResource(engine);
        this.feed = new FeedResource(engine);
        this.batches = new BatchesResource(engine);
        this.esf = new EsfResource(engine);
        this.forms = new FormsResource(engine);
        this.onec = new OnecResource(engine);
        this.automations = new AutomationsResource(engine);
        this.async = new TungoAsyncClient(this, engine);
    }

    /**
     * Starts building a client.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a client configured only from the environment variables {@code TUNGO_API_KEY},
     * {@code TUNGO_BASE_URL} and {@code TUNGO_WEBHOOK_SECRET}.
     *
     * @return a new client
     */
    public static TungoClient fromEnv() {
        return new Builder().build();
    }

    /**
     * Returns the asynchronous view of this client. It shares the configuration, the connection
     * pool and the lifecycle of this client.
     *
     * @return the async client
     */
    public TungoAsyncClient async() {
        return async;
    }

    /**
     * Организации (workspace), доступные ключу API.
     *
     * @return the <code>workspaces</code> resource
     */
    public WorkspacesResource workspaces() {
        return workspaces;
    }

    /**
     * Компании организации и их карточки.
     *
     * @return the <code>companies</code> resource
     */
    public CompaniesResource companies() {
        return companies;
    }

    /**
     * Баланс платформы, книга движений, отчёты о расходе, лимиты, аудит и тарифы моделей.
     *
     * @return the <code>platform</code> resource
     */
    public PlatformResource platform() {
        return platform;
    }

    /**
     * Сведения о текущем ключе API и статистика его расхода.
     *
     * @return the <code>apiKeys</code> resource
     */
    public ApiKeysResource apiKeys() {
        return apiKeys;
    }

    /**
     * Исходящие вебхуки организации (Svix): адреса, каталог событий, доставки и проверка подписи.
     *
     * @return the <code>webhooks</code> resource
     */
    public WebhooksResource webhooks() {
        return webhooks;
    }

    /**
     * Диалоги с ИИ-бухгалтером: вопросы, потоковый чат (SSE), подсчёт токенов и история.
     *
     * @return the <code>chat</code> resource
     */
    public ChatResource chat() {
        return chat;
    }

    /**
     * Предложения документов ИИ-бухгалтера: просмотр, одобрение записи в 1С и отклонение.
     *
     * @return the <code>proposals</code> resource
     */
    public ProposalsResource proposals() {
        return proposals;
    }

    /**
     * Лента событий и предложений компании (страницы и живой поток SSE).
     *
     * @return the <code>feed</code> resource
     */
    public FeedResource feed() {
        return feed;
    }

    /**
     * Пачки обработки банковских выписок, входящих ЭСФ и DirectBank.
     *
     * @return the <code>batches</code> resource
     */
    public BatchesResource batches() {
        return batches;
    }

    /**
     * Входящие и исходящие электронные счета-фактуры (ИС ЭСФ).
     *
     * @return the <code>esf</code> resource
     */
    public EsfResource esf() {
        return esf;
    }

    /**
     * Налоговые формы (ФНО 100.00 и др.): сборка, проверка ИСНА и разрывы данных.
     *
     * @return the <code>forms</code> resource
     */
    public FormsResource forms() {
        return forms;
    }

    /**
     * Состояние подключения компании к базе 1С.
     *
     * @return the <code>onec</code> resource
     */
    public OnecResource onec() {
        return onec;
    }

    /**
     * Автоматизации компании: входящие хуки, сценарии, цепочки шагов, запуски и уровень автономности
     * ИИ.
     *
     * @return the <code>automations</code> resource
     */
    public AutomationsResource automations() {
        return automations;
    }

    /**
     * Asks the AI accountant one question and waits for the complete answer (a non-streaming
     * chat turn, default timeout 600 s).
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @return the result of the turn
     */
    public ChatTurnResult ask(String companyId, String message) {
        return ask(companyId, message, RequestOptions.none());
    }

    /**
     * Asks the AI accountant one question and waits for the complete answer (a non-streaming
     * chat turn, default timeout 600 s).
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @param options per-request options
     * @return the result of the turn
     */
    public ChatTurnResult ask(String companyId, String message, RequestOptions options) {
        return chat().create(companyId, ChatMessageRequest.builder().message(message).build(), options);
    }

    /**
     * Asks the AI accountant one question and streams the answer as it is produced.
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @return the open stream; close it when done
     */
    public ChatStream askStream(String companyId, String message) {
        return chat().createStream(companyId, ChatMessageRequest.builder().message(message).build());
    }

    /**
     * Posts a JSON event to an incoming hook ({@code POST /hooks/{token}}). The API key is never
     * sent to hooks.
     *
     * @param token the hook token from the hook URL
     * @param payload any value Jackson can serialize
     * @param secret the hook secret that signs the exact bytes sent
     *     ({@code X-Signature: sha256=<hex>}); null sends the request unsigned
     * @return the acknowledgement of the hook
     */
    public AutomationHookAccepted sendHook(String token, Object payload, String secret) {
        return automations().postHook(token, HookRequest.json(payload).secret(secret).build());
    }

    /**
     * Posts an event to an incoming hook ({@code POST /hooks/{token}}). The API key is never
     * sent to hooks.
     *
     * @param token the hook token from the hook URL
     * @param request the body, its content type, and optionally a secret and an event id
     * @return the acknowledgement of the hook
     */
    public AutomationHookAccepted sendHook(String token, HookRequest request) {
        return automations().postHook(token, request);
    }

    /**
     * Posts an event to an incoming hook ({@code POST /hooks/{token}}). The API key is never
     * sent to hooks.
     *
     * @param token the hook token from the hook URL
     * @param request the body, its content type, and optionally a secret and an event id
     * @param options per-request options
     * @return the acknowledgement of the hook
     */
    public AutomationHookAccepted sendHook(String token, HookRequest request, RequestOptions options) {
        return automations().postHook(token, request, options);
    }

    /**
     * Verifies a webhook with the client's webhook secret ({@code TUNGO_WEBHOOK_SECRET}) and
     * returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers ({@code webhook-*} or {@code svix-*}); names are
     *     case-insensitive, values may be strings or collections of strings
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     *     or no secret is configured
     */
    public WebhookEvent verifyWebhook(String body, Map<String, ?> headers) {
        return webhookVerifier().verify(body, headers);
    }

    /**
     * Verifies a webhook with the client's webhook secret ({@code TUNGO_WEBHOOK_SECRET}) and
     * returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headerLookup returns the value of a header by name (for example
     *     {@code request::getHeader}); must be case-insensitive
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     *     or no secret is configured
     */
    public WebhookEvent verifyWebhook(String body, Function<String, String> headerLookup) {
        return webhookVerifier().verify(body, headerLookup);
    }

    /**
     * Verifies a webhook from the bytes of its body with the client's webhook secret
     * ({@code TUNGO_WEBHOOK_SECRET}) and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers; names are case-insensitive
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verifyWebhook(byte[] body, Map<String, ?> headers) {
        return webhookVerifier().verify(body, headers);
    }

    /**
     * Returns a webhook verifier bound to the client's webhook secret and clock.
     *
     * @return the verifier; adjust it with {@code withTolerance} if needed
     */
    public WebhookVerifier webhookVerifier() {
        return WebhookVerifier.of(engine.config().webhookSecret).withClock(engine.config().clock);
    }

    /**
     * Waits until an automation run reaches a final state (done, error, skipped or cancelled),
     * polling with the default options.
     *
     * @param companyId id of the company ({@code co_...})
     * @param runId id of the run
     * @return the run in its final state
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public AutomationRunDetail waitForRun(String companyId, String runId) {
        return waitForRun(companyId, runId, WaitOptions.defaults());
    }

    /**
     * Waits until an automation run reaches a final state (done, error, skipped or cancelled).
     * A state this SDK does not know keeps the wait going; a missing state is a decode error.
     *
     * @param companyId id of the company ({@code co_...})
     * @param runId id of the run
     * @param options deadline, polling intervals and request options
     * @return the run in its final state
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public AutomationRunDetail waitForRun(String companyId, String runId, WaitOptions options) {
        WaitOptions opts = options == null ? WaitOptions.defaults() : options;
        return Polling.poll(opts, () -> automations().getRun(companyId, runId, opts.requestOptions()), run -> {
            if (run.state() == null) {
                throw new DecodeException("The run " + runId + " has no state", null);
            }
            return isFinalRunState(run.state().value());
        }, "run " + runId);
    }

    /**
     * Starts a scenario manually and waits until its run reaches a final state, with the default
     * options.
     *
     * @param companyId id of the company ({@code co_...})
     * @param scenarioId id of the scenario
     * @return the run in its final state
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public AutomationRunDetail runScenarioAndWait(String companyId, String scenarioId) {
        return runScenarioAndWait(companyId, scenarioId, null, WaitOptions.defaults());
    }

    /**
     * Starts a scenario manually and waits until its run reaches a final state.
     *
     * @param companyId id of the company ({@code co_...})
     * @param scenarioId id of the scenario
     * @param input the optional run input, may be null
     * @param options deadline, polling intervals and request options
     * @return the run in its final state
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public AutomationRunDetail runScenarioAndWait(String companyId, String scenarioId, AutomationScenarioRunInput input,
        WaitOptions options) {
        WaitOptions opts = options == null ? WaitOptions.defaults() : options;
        AutomationManualRun run = automations().runScenario(companyId, scenarioId, input, opts.requestOptions());
        if (run.id() == null || run.id().isEmpty()) {
            throw new DecodeException("The started run has no id", null);
        }
        return waitForRun(companyId, run.id(), opts);
    }

    /**
     * Waits until a batch job stops running, polling with the default options.
     *
     * @param companyId id of the company ({@code co_...})
     * @param batchId id of the batch job
     * @return the finished job
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public BatchJob waitForBatch(String companyId, String batchId) {
        return waitForBatch(companyId, batchId, WaitOptions.defaults());
    }

    /**
     * Waits until a batch job stops running ({@code running} is false). A response without the
     * {@code running} flag is a decode error, not a finished job.
     *
     * @param companyId id of the company ({@code co_...})
     * @param batchId id of the batch job
     * @param options deadline, polling intervals and request options
     * @return the finished job
     * @throws kz.tungo.sdk.errors.WaitTimeoutException when the deadline passes first
     */
    public BatchJob waitForBatch(String companyId, String batchId, WaitOptions options) {
        WaitOptions opts = options == null ? WaitOptions.defaults() : options;
        return Polling.poll(opts, () -> batches().get(companyId, batchId, opts.requestOptions()), job -> {
            if (job.running() == null) {
                throw new DecodeException("The batch job " + batchId + " has no running flag", null);
            }
            return !job.running();
        }, "batch job " + batchId);
    }

    private static boolean isFinalRunState(String state) {
        return state.equals("done") || state.equals("error") || state.equals("skipped") || state.equals("cancelled");
    }

    /**
     * Releases the resources the client created (threads and, on Java 21 and later, its
     * connections). A caller-supplied HTTP client or executor is left untouched. Open streams
     * should be closed first.
     */
    @Override
    public void close() {
        engine.close();
    }

    /**
     * Describes the client without revealing the API key or the webhook secret.
     *
     * @return the base URL and whether credentials are set
     */
    @Override
    public String toString() {
        ClientConfig c = engine.config();
        return "TungoClient{baseUrl=" + c.baseUrl + ", apiKey=" + (c.apiKey == null ? "not set" : "***")
            + ", webhookSecret=" + (c.webhookSecret == null ? "not set" : "***") + "}";
    }

    /** Builder of {@link TungoClient}. Not thread-safe; the client it builds is. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String webhookSecret;
        private Duration timeout;
        private Duration connectTimeout = ClientConfig.DEFAULT_CONNECT_TIMEOUT;
        private Duration streamIdleTimeout = ClientConfig.DEFAULT_STREAM_IDLE_TIMEOUT;
        private int maxRetries = ClientConfig.DEFAULT_MAX_RETRIES;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private HttpClient httpClient;
        private Executor asyncExecutor;
        private int asyncMaxThreads = ClientConfig.DEFAULT_ASYNC_MAX_THREADS;
        private Clock clock = Clock.systemUTC();
        private Function<String, String> env = System::getenv;

        private Builder() {
        }

        /**
         * Sets the API key. Without it the {@code TUNGO_API_KEY} environment variable is used.
         *
         * @param apiKey the key ({@code tg_...})
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the base URL. Without it the {@code TUNGO_BASE_URL} environment variable is used,
         * then {@code https://api.tungo.kz}.
         *
         * @param baseUrl an absolute http or https URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the webhook secret used by {@link TungoClient#verifyWebhook}. Without it the
         * {@code TUNGO_WEBHOOK_SECRET} environment variable is used.
         *
         * @param webhookSecret the endpoint secret ({@code whsec_...})
         * @return this builder
         */
        public Builder webhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
            return this;
        }

        /**
         * Sets the timeout of every non-streaming request, covering the whole request including
         * reading the body. The default is 60 s, and 600 s for non-streaming chat turns.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = positive(timeout, "timeout");
            return this;
        }

        /**
         * Sets the connect timeout of the built-in HTTP client (default 30 s). It has no effect
         * when {@link #httpClient(HttpClient)} is used.
         *
         * @param connectTimeout a positive duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = positive(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * Sets the longest pause without any bytes a streaming call tolerates (default 120 s).
         *
         * @param streamIdleTimeout a positive duration
         * @return this builder
         */
        public Builder streamIdleTimeout(Duration streamIdleTimeout) {
            this.streamIdleTimeout = positive(streamIdleTimeout, "streamIdleTimeout");
            return this;
        }

        /**
         * Sets the maximum number of retries (default 2, that is 3 attempts); 0 disables retries.
         *
         * @param maxRetries zero or more
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Adds a header sent with every request.
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder defaultHeader(String name, String value) {
            defaultHeaders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Uses a caller-supplied {@link HttpClient} (proxy, TLS settings, HTTP version, ...). The
         * SDK does not close it.
         *
         * <p>The client must not follow redirects (the default of {@link HttpClient}): the API key
         * travels in a header that would be forwarded to the host a redirect names. A 3xx answer
         * is an {@code ApiException} with that status.
         *
         * @param httpClient the HTTP client
         * @return this builder
         * @throws ConfigException if the client follows redirects
         */
        public Builder httpClient(HttpClient httpClient) {
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new ConfigException("The HTTP client must not follow redirects: build it with"
                    + " followRedirects(HttpClient.Redirect.NEVER), otherwise a redirect would carry the API key to another host");
            }
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the executor on which the async client runs its calls. Every async method runs the
         * blocking call on this executor, so one call occupies one of its threads until the
         * response is read. The default is a bounded pool of daemon threads owned by the client
         * (see {@link #asyncMaxThreads(int)}). On Java 21 and later a virtual-thread executor
         * ({@code Executors.newVirtualThreadPerTaskExecutor()}) is a good choice. The SDK does not
         * shut a supplied executor down.
         *
         * @param asyncExecutor the executor; null restores the built-in pool
         * @return this builder
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        /**
         * Sets the size of the built-in pool of the async client (default 64): at most this many
         * async calls run at once, further calls wait in a queue. Idle threads end after 30 s and
         * the threads are daemons. It has no effect when {@link #asyncExecutor(Executor)} is used.
         *
         * @param asyncMaxThreads a positive number of threads
         * @return this builder
         */
        public Builder asyncMaxThreads(int asyncMaxThreads) {
            if (asyncMaxThreads < 1) {
                throw new ConfigException("asyncMaxThreads must be at least 1");
            }
            this.asyncMaxThreads = asyncMaxThreads;
            return this;
        }

        /**
         * Sets the clock used to interpret HTTP-date headers and webhook timestamps (for tests).
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Replaces the source of environment variables (for tests). */
        Builder environment(Function<String, String> env) {
            this.env = env;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return an immutable, thread-safe client
         * @throws ConfigException when the base URL is not an absolute http or https URL
         */
        public TungoClient build() {
            String key = firstNonEmpty(apiKey, env.apply("TUNGO_API_KEY"));
            // A value the JDK rejects would fail every call, and its error quotes the value: name it here.
            if (key != null && !HttpEngine.isHeaderValueSafe(key)) {
                throw new ConfigException("The API key contains a control character (such as a trailing newline"
                    + " from a secret file); remove it");
            }
            for (Map.Entry<String, String> h : defaultHeaders.entrySet()) {
                if (!HttpEngine.isHeaderValueSafe(h.getValue())) {
                    throw new ConfigException("The value of default header '" + h.getKey()
                        + "' contains a control character (such as a trailing newline)");
                }
            }
            String secret = firstNonEmpty(webhookSecret, env.apply("TUNGO_WEBHOOK_SECRET"));
            String url = firstNonEmpty(baseUrl, env.apply("TUNGO_BASE_URL"));
            if (url == null) {
                url = SdkVersion.DEFAULT_BASE_URL;
            }
            url = url.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("The base URL is not a valid URL: " + url, e);
            }
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new ConfigException("The base URL must be an absolute http or https URL without a query: " + url);
            }
            return new TungoClient(new ClientConfig(key, url, secret, timeout, connectTimeout, streamIdleTimeout, maxRetries,
                Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders)), httpClient, asyncExecutor, asyncMaxThreads, clock));
        }

        private static String firstNonEmpty(String a, String b) {
            if (a != null && !a.isEmpty()) {
                return a;
            }
            return b != null && !b.isEmpty() ? b : null;
        }

        static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
