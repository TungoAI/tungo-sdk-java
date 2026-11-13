package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.TungoException;
import kz.tungo.sdk.models.Company;
import org.junit.jupiter.api.Test;

class ClientConfigTest {
    private static final String COMPANY = "{\"id\":\"co_1\",\"permissions\":[],\"name\":\"Ромашка\",\"bin\":\"990140000017\"}";

    @Test
    void defaultBaseUrlIsTheProductionApi() {
        assertEquals("https://api.tungo.kz", SdkVersion.DEFAULT_BASE_URL);
        try (TungoClient client = TungoClient.builder().environment(name -> null).apiKey("tg_x").build()) {
            assertTrue(client.toString().contains("https://api.tungo.kz"), client.toString());
        }
    }

    @Test
    void missingApiKeyIsALocalConfigurationErrorBeforeAnyRequest() {
        try (MockServer server = new MockServer();
            TungoClient client = TungoClient.builder().environment(name -> null).baseUrl(server.url()).build()) {
            ConfigException e = assertThrows(ConfigException.class, () -> client.companies().list());
            assertTrue(e.getMessage().contains("TUNGO_API_KEY"), e.getMessage());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void aKeyOrHeaderWithAControlCharacterIsRejectedWithoutQuotingIt() {
        String secret = "tg_live_SECRETSECRET";
        // A trailing newline from a secret file or `echo`, given directly or through the environment.
        ConfigException direct = assertThrows(ConfigException.class,
            () -> TungoClient.builder().environment(name -> null).apiKey(secret + "\n").build());
        assertFalse(direct.getMessage().contains(secret), direct.getMessage());
        assertNull(direct.getCause());
        Map<String, String> env = new HashMap<>();
        env.put("TUNGO_API_KEY", secret + "\r\n");
        ConfigException fromEnv = assertThrows(ConfigException.class, () -> TungoClient.builder().environment(env::get).build());
        assertFalse(fromEnv.getMessage().contains(secret), fromEnv.getMessage());
        ConfigException header = assertThrows(ConfigException.class,
            () -> TungoClient.builder().environment(name -> null).apiKey("tg_x").defaultHeader("X-Token", secret + "\u0000").build());
        assertFalse(header.getMessage().contains(secret), header.getMessage());
        assertTrue(header.getMessage().contains("X-Token"), header.getMessage());

        // A per-request header is checked when the request is built: named, never quoted, sent nowhere.
        try (MockServer server = new MockServer();
            TungoClient client = TungoClient.builder().environment(name -> null).apiKey("tg_x").baseUrl(server.url()).build()) {
            TungoException e = assertThrows(TungoException.class,
                () -> client.companies().get("co_1", RequestOptions.builder().header("X-Token", secret + "\n").build()));
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains(secret), t.toString());
            }
            assertTrue(e.getMessage().contains("X-Token"), e.getMessage());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void readsKeyBaseUrlAndWebhookSecretFromTheEnvironment() {
        try (MockServer server = new MockServer()) {
            Map<String, String> env = new HashMap<>();
            env.put("TUNGO_API_KEY", "tg_env_key");
            env.put("TUNGO_BASE_URL", server.url() + "/");
            env.put("TUNGO_WEBHOOK_SECRET", "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
            server.enqueueJson(200, COMPANY);
            try (TungoClient client = TungoClient.builder().environment(env::get).build()) {
                assertEquals("co_1", client.companies().get("co_1").id());
                assertEquals("tg_env_key", server.request(0).header("x-api-key"));
                assertEquals("/v1/companies/co_1", server.request(0).path());
                assertTrue(client.toString().contains("webhookSecret=***"), client.toString());
            }
        }
    }

    @Test
    void explicitOptionsWinOverTheEnvironment() {
        try (MockServer server = new MockServer()) {
            Map<String, String> env = new HashMap<>();
            env.put("TUNGO_API_KEY", "tg_env_key");
            env.put("TUNGO_BASE_URL", "http://127.0.0.1:1");
            server.enqueueJson(200, COMPANY);
            try (TungoClient client = TungoClient.builder().environment(env::get).apiKey("tg_explicit").baseUrl(server.url()).build()) {
                client.companies().get("co_1");
                assertEquals("tg_explicit", server.request(0).header("x-api-key"));
            }
        }
    }

    @Test
    void rejectsABaseUrlThatIsNotAbsoluteHttp() {
        for (String bad : new String[] {"ftp://example.com", "api.tungo.kz", "http://", "https://api.tungo.kz?x=1", "ht tp://x"}) {
            assertThrows(ConfigException.class,
                () -> TungoClient.builder().environment(name -> null).apiKey("k").baseUrl(bad).build(), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> TungoClient.builder().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> TungoClient.builder().maxRetries(-1));
    }

    @Test
    void sendsTheStandardHeadersAndOnlyTheApiKeyHeader() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).defaultHeader("X-Team", "ledger").build()) {
            server.enqueueJson(200, COMPANY);
            client.companies().get("co_1", RequestOptions.builder().header("X-Trace", "t-1").build());
            MockServer.Request request = server.request(0);
            assertEquals("GET", request.method);
            assertEquals(TestSupport.API_KEY, request.header("x-api-key"));
            assertNull(request.header("authorization"));
            assertEquals("tungo-sdk-java/" + SdkVersion.VERSION, request.header("user-agent"));
            assertEquals(SdkVersion.USER_AGENT, request.header("user-agent"));
            assertEquals("application/json", request.header("accept"));
            assertEquals("ledger", request.header("x-team"));
            assertEquals("t-1", request.header("x-trace"));
            assertNull(request.header("content-type"));
            assertNull(request.header("idempotency-key"));
        }
    }

    @Test
    void publicEndpointsAndHooksNeverGetTheApiKey() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).defaultHeader("Authorization", "Bearer leaked").build()) {
            server.enqueueJson(200, "{\"data\":[]}");
            client.platform().listUsageRates();
            assertNull(server.request(0).header("x-api-key"));
            assertNull(server.request(0).header("authorization"));

            server.enqueueJson(202, "{\"accepted\":true,\"run_ids\":[]}");
            client.sendHook("hk_token", Map.of("id", 1), "hksec_1");
            assertEquals("/hooks/hk_token", server.request(1).path());
            assertNull(server.request(1).header("x-api-key"));
            assertNull(server.request(1).header("authorization"));
        }
    }

    @Test
    void publicEndpointsWorkWithoutAnApiKey() {
        try (MockServer server = new MockServer();
            TungoClient client = TungoClient.builder().environment(name -> null).baseUrl(server.url()).build()) {
            server.enqueueJson(200, "{\"data\":[]}");
            assertNotNull(client.platform().listUsageRates());
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void theApiKeyNeverAppearsInMessagesOrToString() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).maxRetries(0).webhookSecret("whsec_c2VjcmV0c2VjcmV0").build()) {
            assertFalse(client.toString().contains(TestSupport.API_KEY));
            assertFalse(client.toString().contains("whsec_"));
            assertFalse(client.async().toString().contains(TestSupport.API_KEY));

            server.enqueueJson(401, TestSupport.envelope("authentication_error", "invalid_api_key", "Bad key"));
            ApiException api = assertThrows(ApiException.class, () -> client.companies().list());
            assertFalse(api.toString().contains(TestSupport.API_KEY));

            server.always((request, exchange) -> exchange.abort());
            TungoException io = assertThrows(ConnectionException.class, () -> client.companies().list());
            StringBuilder all = new StringBuilder();
            for (Throwable t = io; t != null; t = t.getCause()) {
                all.append(t);
            }
            assertFalse(all.toString().contains(TestSupport.API_KEY), all.toString());
        }
    }

    @Test
    void everyCallGivesAccessToTheRawResponse() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, COMPANY, "x-request-id", "req_42", "x-ratelimit-limit", "60",
                "x-ratelimit-remaining", "59", "x-ratelimit-reset", "7", "Idempotent-Replayed", "true");
            ApiResponse<Company> response = client.companies().getWithResponse("co_1", RequestOptions.none());
            assertEquals(200, response.statusCode());
            assertEquals("req_42", response.requestId().orElseThrow());
            assertEquals("co_1", response.body().id());
            assertEquals(60, response.rateLimit().orElseThrow().limit());
            assertEquals(59, response.rateLimit().orElseThrow().remaining());
            assertEquals(7, response.rateLimit().orElseThrow().reset());
            assertEquals("60", response.header("X-RateLimit-Limit").orElseThrow());

            server.enqueueJson(200, COMPANY);
            assertFalse(client.companies().getWithResponse("co_1", null).rateLimit().isPresent());
        }
    }

    @Test
    void usesTheInjectedHttpClientAndDoesNotCloseIt() {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try (MockServer server = new MockServer()) {
            server.always((request, exchange) -> exchange.json(200, COMPANY));
            try (TungoClient client = TestSupport.builder(server).httpClient(http).build()) {
                client.companies().get("co_1");
                assertNull(server.request(0).header("upgrade"), "the injected HTTP/1.1 client must be used");
            }
            try (TungoClient client = TestSupport.builder(server).httpClient(http).build()) {
                assertEquals("co_1", client.companies().get("co_1").id());
            }
        }
    }

    @Test
    void reusesConnections() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, COMPANY));
            for (int i = 0; i < 5; i++) {
                client.companies().get("co_1");
            }
            assertEquals(5, server.requestCount());
            assertEquals(1, server.connectionCount());
        }
    }

    @Test
    void aClosedClientRefusesNewCalls() {
        try (MockServer server = new MockServer()) {
            TungoClient client = TestSupport.client(server);
            client.close();
            client.close();
            assertThrows(IllegalStateException.class, () -> client.companies().list());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void oneClientIsSafeToShareAcrossThreads() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                String id = request.path().substring(request.path().lastIndexOf('/') + 1);
                exchange.json(200, "{\"id\":\"" + id + "\",\"name\":\"n\",\"permissions\":[]}", "x-request-id", "req_" + id);
            });
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    String id = "co_" + i;
                    Callable<Boolean> call = () -> {
                        ApiResponse<Company> r = client.companies().getWithResponse(id, null);
                        return r.body().id().equals(id) && r.requestId().orElse("").equals("req_" + id);
                    };
                    results.add(pool.submit(call));
                }
                for (Future<Boolean> result : results) {
                    assertTrue(result.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
