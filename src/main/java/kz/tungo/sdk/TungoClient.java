package kz.tungo.sdk;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TungoClient {
    public record SSEEvent(String event, String raw) {}

    private final String apiKey;
    private final String baseUrl;
    private final String workspaceId;
    private final String companyId;
    private final HttpClient httpClient;

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
        this.apiKey = apiKey != null ? apiKey : System.getenv("TUNGO_API_KEY");
        String b = baseUrl != null ? baseUrl : System.getenv().getOrDefault("TUNGO_BASE_URL", "https://api.tungo.ai");
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.workspaceId = workspaceId != null ? workspaceId : System.getenv("TUNGO_WORKSPACE_ID");
        this.companyId = companyId != null ? companyId : System.getenv("TUNGO_COMPANY_ID");
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

    public Map<String, Object> request(
        String method,
        String path,
        Map<String, String> query,
        Map<String, Object> body,
        String auth,
        boolean idempotent,
        boolean retrySafe
    ) {
        return new HashMap<>();
    }

    public List<SSEEvent> requestStream(
        String method,
        String path,
        Map<String, String> query,
        Map<String, Object> body,
        String auth
    ) {
        return List.of();
    }
}
