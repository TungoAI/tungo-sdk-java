package kz.tungo.sdk;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TungoClient {
    public record SSEEvent(String event, String raw) {}
    public record RateLimitInfo(long limit, long remaining, long reset) {}

    private final String apiKey;
    private final String baseUrl;
    private final String workspaceId;
    private final String companyId;
    private final int maxRetries;
    private final HttpClient httpClient;
    private volatile RateLimitInfo lastRateLimit;

    private final TungoResources.WorkspacesResource workspaces;
    private final TungoResources.CompaniesResource companies;
    private final TungoResources.PlatformResource platform;
    private final TungoResources.ApiKeysResource apiKeys;
    private final TungoResources.WebhooksResource webhooks;
    private final TungoResources.ChatResource chat;
    private final TungoResources.ProposalsResource proposals;
    private final TungoResources.FeedResource feed;
    private final TungoResources.BatchesResource batches;
    private final TungoResources.EsfResource esf;
    private final TungoResources.FormsResource forms;
    private final TungoResources.OnecResource onec;
    private final TungoResources.AutomationsResource automations;

    public TungoClient(String apiKey, String baseUrl, String workspaceId, String companyId) {
        this(apiKey, baseUrl, workspaceId, companyId, 2);
    }

    public TungoClient(String apiKey, String baseUrl, String workspaceId, String companyId, int maxRetries) {
        this.apiKey = apiKey != null ? apiKey : System.getenv("TUNGO_API_KEY");
        String b = baseUrl != null ? baseUrl : System.getenv().getOrDefault("TUNGO_BASE_URL", "https://api.tungo.kz");
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.workspaceId = workspaceId != null ? workspaceId : System.getenv("TUNGO_WORKSPACE_ID");
        this.companyId = companyId != null ? companyId : System.getenv("TUNGO_COMPANY_ID");
        this.maxRetries = Math.max(0, maxRetries);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.workspaces = new TungoResources.WorkspacesResource(this);
        this.companies = new TungoResources.CompaniesResource(this);
        this.platform = new TungoResources.PlatformResource(this);
        this.apiKeys = new TungoResources.ApiKeysResource(this);
        this.webhooks = new TungoResources.WebhooksResource(this);
        this.chat = new TungoResources.ChatResource(this);
        this.proposals = new TungoResources.ProposalsResource(this);
        this.feed = new TungoResources.FeedResource(this);
        this.batches = new TungoResources.BatchesResource(this);
        this.esf = new TungoResources.EsfResource(this);
        this.forms = new TungoResources.FormsResource(this);
        this.onec = new TungoResources.OnecResource(this);
        this.automations = new TungoResources.AutomationsResource(this);
    }

    public TungoResources.WorkspacesResource workspaces() { return workspaces; }
    public TungoResources.CompaniesResource companies() { return companies; }
    public TungoResources.PlatformResource platform() { return platform; }
    public TungoResources.ApiKeysResource apiKeys() { return apiKeys; }
    public TungoResources.WebhooksResource webhooks() { return webhooks; }
    public TungoResources.ChatResource chat() { return chat; }
    public TungoResources.ProposalsResource proposals() { return proposals; }
    public TungoResources.FeedResource feed() { return feed; }
    public TungoResources.BatchesResource batches() { return batches; }
    public TungoResources.EsfResource esf() { return esf; }
    public TungoResources.FormsResource forms() { return forms; }
    public TungoResources.OnecResource onec() { return onec; }
    public TungoResources.AutomationsResource automations() { return automations; }

    public RateLimitInfo lastRateLimit() { return lastRateLimit; }

    public String resolveCompanyId(String explicit) {
        String co = (explicit != null && !explicit.isEmpty()) ? explicit : companyId;
        if (co == null || co.isEmpty()) {
            throw new TungoError(0, "missing_company_id", "Не указан companyId", null, null, null);
        }
        return co;
    }

    public String resolveWorkspaceId(String explicit) {
        String ws = (explicit != null && !explicit.isEmpty()) ? explicit : workspaceId;
        if (ws == null || ws.isEmpty()) {
            throw new TungoError(0, "missing_workspace_id", "Не указан workspaceId", null, null, null);
        }
        return ws;
    }

    /** Быстрый синхронный запрос к ИИ-бухгалтеру (stream = false). */
    public Map<String, Object> ask(String prompt, String companyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", prompt);
        body.put("stream", false);
        Map<String, String> params = new HashMap<>();
        if (companyId != null && !companyId.isEmpty()) {
            params.put("co", companyId);
        }
        return chat().create(body, params);
    }

    /** Отправить событие во входящий хук /hooks/{token} с HMAC-SHA256 подписью. */
    public Map<String, Object> sendHook(String token, Map<String, Object> payload, String secret) {
        Map<String, String> params = new HashMap<>();
        if (secret != null && !secret.isEmpty()) {
            params.put("X-Signature", TungoWebhooks.signHookPayload(toJson(payload), secret));
        }
        return automations().postHook(token, payload, params);
    }

    /** Дождаться завершения запуска сценария (state in done/error/cancelled/skipped). */
    public Map<String, Object> waitForRun(String runId, String companyId, long timeoutMs) {
        Map<String, String> params = new HashMap<>();
        if (companyId != null && !companyId.isEmpty()) params.put("co", companyId);
        long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : 120_000L);
        while (true) {
            Map<String, Object> run = automations().getRun(runId, params);
            Object st = run != null ? run.get("state") : null;
            if ("done".equals(st) || "error".equals(st) || "cancelled".equals(st) || "skipped".equals(st)) {
                return run;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new TungoError(0, "timeout", "Превышено время ожидания запуска " + runId, null, null, null);
            }
            try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TungoError(0, "interrupted", e.getMessage(), null, null, null); }
        }
    }

    /** Дождаться завершения задания пачки (running == false). */
    public Map<String, Object> waitForBatch(String batchId, String companyId, long timeoutMs) {
        Map<String, String> params = new HashMap<>();
        if (companyId != null && !companyId.isEmpty()) params.put("co", companyId);
        long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : 120_000L);
        while (true) {
            Map<String, Object> job = batches().get(batchId, params);
            Object running = job != null ? job.get("running") : null;
            if (!Boolean.TRUE.equals(running)) {
                return job;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new TungoError(0, "timeout", "Превышено время ожидания пачки " + batchId, null, null, null);
            }
            try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TungoError(0, "interrupted", e.getMessage(), null, null, null); }
        }
    }

    public static List<SSEEvent> parseSse(String rawStream) {
        List<SSEEvent> events = new ArrayList<>();
        String eventType = "";
        List<String> dataLines = new ArrayList<>();
        for (String rawLine : rawStream.split("\\r?\\n", -1)) {
            if (rawLine.isEmpty()) {
                if (!eventType.isEmpty() || !dataLines.isEmpty()) {
                    events.add(new SSEEvent(eventType.isEmpty() ? "message" : eventType, String.join("\n", dataLines)));
                    eventType = "";
                    dataLines.clear();
                }
                continue;
            }
            if (rawLine.startsWith(":")) continue;
            if (rawLine.startsWith("event:")) {
                eventType = rawLine.substring(6).trim();
            } else if (rawLine.startsWith("data:")) {
                String v = rawLine.substring(5);
                if (v.startsWith(" ")) v = v.substring(1);
                dataLines.add(v);
            }
        }
        if (!eventType.isEmpty() || !dataLines.isEmpty()) {
            events.add(new SSEEvent(eventType.isEmpty() ? "message" : eventType, String.join("\n", dataLines)));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> request(
        String method,
        String path,
        Map<String, String> query,
        Map<String, Object> body,
        String auth,
        boolean idempotent,
        boolean retrySafe
    ) {
        String raw = executeHttp(method, path, query, body, auth, idempotent, retrySafe, "application/json");
        if (raw == null || raw.isBlank()) return new HashMap<>();
        Object parsed = parseJson(raw);
        if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> wrap = new HashMap<>();
        wrap.put("data", parsed);
        return wrap;
    }

    public List<SSEEvent> requestStream(
        String method,
        String path,
        Map<String, String> query,
        Map<String, Object> body,
        String auth
    ) {
        String raw = executeHttp(method, path, query, body, auth, false, false, "text/event-stream");
        return parseSse(raw);
    }

    private String executeHttp(
        String method,
        String path,
        Map<String, String> query,
        Map<String, Object> body,
        String auth,
        boolean idempotent,
        boolean retrySafe,
        String accept
    ) {
        Map<String, String> q = query != null ? new HashMap<>(query) : new HashMap<>();
        String idemKey = q.remove("idempotency_key");
        if (idemKey == null && idempotent) idemKey = UUID.randomUUID().toString();
        String sigHeader = q.remove("X-Signature");

        StringBuilder url = new StringBuilder(baseUrl).append(path);
        if (!q.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : q.entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) url.append('&');
                first = false;
                url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                   .append('=')
                   .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }

        String jsonBody = body != null ? toJson(body) : null;
        int maxAttempts = (retrySafe || idempotent || "GET".equals(method)) ? (1 + maxRetries) : 1;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .header("User-Agent", "tungo-sdk-java/0.1.0");
            if ("key".equals(auth)) {
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new TungoError(401, "missing_api_key", "API ключ не задан", null, null, null);
                }
                rb.header("x-api-key", apiKey).header("Authorization", "Bearer " + apiKey);
            }
            if (idemKey != null && !idemKey.isEmpty()) rb.header("Idempotency-Key", idemKey);
            if (sigHeader != null && !sigHeader.isEmpty()) rb.header("X-Signature", sigHeader);
            if (jsonBody != null) {
                rb.header("Content-Type", "application/json");
                rb.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }

            try {
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = resp.statusCode();
                String lim = resp.headers().firstValue("x-ratelimit-limit").orElse(null);
                String rem = resp.headers().firstValue("x-ratelimit-remaining").orElse(null);
                String rst = resp.headers().firstValue("x-ratelimit-reset").orElse(null);
                if (lim != null && rem != null && rst != null) {
                    try {
                        lastRateLimit = new RateLimitInfo(Long.parseLong(lim), Long.parseLong(rem), Long.parseLong(rst));
                    } catch (NumberFormatException ignored) {}
                }
                if (status >= 200 && status < 300) {
                    return resp.body();
                }
                String reqId = resp.headers().firstValue("x-request-id").orElse(null);
                Double retryAfter = resp.headers().firstValue("retry-after").map(v -> {
                    try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
                }).orElse(null);
                if (attempt + 1 < maxAttempts && (status == 429 || status == 502 || status == 503 || status == 504)) {
                    long sleepMs = retryAfter != null ? (long) (retryAfter * 1000) : (250L * (1L << attempt));
                    Thread.sleep(Math.min(sleepMs, 8000L));
                    continue;
                }
                throw new TungoError(status, "api_error", resp.body(), null, reqId, retryAfter);
            } catch (TungoError e) {
                throw e;
            } catch (Exception e) {
                if (attempt + 1 < maxAttempts) continue;
                throw new TungoError(0, "network_error", e.getMessage(), null, null, null);
            }
        }
        throw new TungoError(0, "max_retries_exceeded", "Превышено число попыток", null, null, null);
    }

    public static String toJson(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (val instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(item));
            }
            return sb.append(']').toString();
        }
        return toJson(val.toString());
    }

    public static Object parseJson(String s) {
        return new HashMap<String, Object>();
    }
}
