package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kz.tungo.examples.ReadmeExamples;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.models.ChatEvent;
import org.junit.jupiter.api.Test;

/** Runs every code sample of the README (examples/kz/tungo/examples/ReadmeExamples.java). */
class ReadmeExamplesTest {
    private static final String COMPANY = "{\"id\":\"co_1\",\"permissions\":[],\"name\":\"Ромашка\"}";
    private static final String TURN = "{\"answer\":\"5 400 ₸\",\"dialog_id\":\"d\",\"turn_no\":1,\"citations\":[],\"errors\":[],\"followups\":[],\"proposals\":[],\"questions\":[]}";
    private static final String STREAM = "event: text\ndata: {\"type\":\"text\",\"delta\":\"НДС \"}\n\n"
        + "event: tool\ndata: {\"type\":\"tool\",\"name\":\"ledger\",\"status\":\"running\"}\n\n"
        + "event: text\ndata: {\"type\":\"text\",\"delta\":\"5 400 ₸\"}\n\n"
        + "event: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\"}\n\n";

    @Test
    void clientCreationSamplesBuildClients() {
        try (TungoClient client = ReadmeExamples.create()) {
            assertTrue(client.toString().contains("https://api.tungo.kz"));
        }
        try (TungoClient client = ReadmeExamples.config(HttpClient.newHttpClient())) {
            assertNotNull(client);
        }
        try (TungoClient client = ReadmeExamples.retries()) {
            assertNotNull(client);
        }
    }

    @Test
    void quickStartAndRequestOptions() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"data\":[" + COMPANY + "]}").enqueueJson(200, TURN);
            assertEquals("5 400 ₸", ReadmeExamples.quickStart(client));
            server.enqueueJson(200, COMPANY);
            assertEquals("co_1", ReadmeExamples.requestOptions(client).id());
            assertEquals("trace-123", server.lastRequest().header("x-trace-id"));
            server.enqueueJson(200, TURN);
            assertEquals("5 400 ₸", ReadmeExamples.retriesPerRequest(client));
            assertEquals("ask-2026-11-09-001", server.lastRequest().header("idempotency-key"));
            server.enqueueJson(200, COMPANY, "x-request-id", "req_1", "x-ratelimit-limit", "60",
                "x-ratelimit-remaining", "59", "x-ratelimit-reset", "1");
            assertEquals("req_1|59|co_1", ReadmeExamples.rawResponse(client));
        }
    }

    @Test
    void errors() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(404, TestSupport.envelope("not_found_error", "not_found", "Нет"));
            assertEquals("not found, request req_test_1", ReadmeExamples.errors(client));
            server.enqueueJson(429, TestSupport.envelope("limit_error", "rate_limited", "Стоп"), "Retry-After", "90");
            assertEquals("limited, retry after PT1M30S", ReadmeExamples.errors(client));
            server.enqueueJson(403, TestSupport.envelope("permission_error", "missing_permission", "Нет права"));
            assertTrue(ReadmeExamples.errors(client).startsWith("403 permission_error missing_permission"));
            server.enqueueJson(200, "oops");
            assertTrue(ReadmeExamples.errors(client).contains("cannot be decoded"));
        }
    }

    @Test
    void pagination() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                if (request.query().contains("cursor=c1")) {
                    exchange.json(200, "{\"data\":[{\"id\":\"p2\",\"kind_title\":\"Счёт\"}],\"counts\":{},\"next_cursor\":null}");
                } else {
                    exchange.json(200, "{\"data\":[{\"id\":\"p1\",\"kind_title\":\"Акт\"}],\"counts\":{},\"next_cursor\":\"c1\"}");
                }
            });
            assertEquals(List.of("p1", "total:2"), ReadmeExamples.pagination(client));
            assertTrue(server.request(0).query().contains("status=pending"));
        }
    }

    @Test
    void streaming() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                exchange.sse("X-Dialog-Id", "dlg-1");
                exchange.write(STREAM);
            });
            assertEquals("НДС 5 400 ₸", ReadmeExamples.streaming(client));
            assertEquals("НДС 5 400 ₸", ReadmeExamples.streamingAccumulate(client));
        }
    }

    @Test
    void webhooksAndHooks() {
        String secret = "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
        String body = "{\"id\":\"evt_1\",\"type\":\"automation.run.completed\",\"data\":{\"run_id\":\"run_1\",\"proposal_ids\":[],\"proposals\":[]}}";
        long ts = Instant.now().getEpochSecond();
        byte[] mac = TungoWebhooks.hmacSha256(Base64.getDecoder().decode(secret.substring(6)),
            ("msg_1." + ts + "." + body).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new HashMap<>();
        headers.put("webhook-id", "msg_1");
        headers.put("webhook-timestamp", Long.toString(ts));
        headers.put("webhook-signature", "v1," + Base64.getEncoder().encodeToString(mac));
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).webhookSecret(secret).build()) {
            assertEquals("automation.run.completed", ReadmeExamples.webhooks(client, body, headers));
            assertEquals("rejected", ReadmeExamples.webhooks(client, body + " ", headers));
            server.enqueueJson(202, "{\"accepted\":true,\"duplicate\":false,\"event_id\":\"order-1532\",\"run_ids\":[]}");
            assertTrue(ReadmeExamples.sendHook(client).accepted());
            assertEquals("order-1532", server.request(0).header("x-event-id"));
            assertTrue(server.request(0).header("x-signature").startsWith("sha256="));
        }
    }

    @Test
    void moneyModelsAsyncAndWaiting() throws Exception {
        assertEquals("1 234,57 ₸|123457|true", ReadmeExamples.money());
        ChatEvent text = Json.decodeText("{\"type\":\"text\",\"delta\":\"x\"}", ChatEvent.class, "test");
        assertEquals("text x|true", ReadmeExamples.models(text));
        ChatEvent other = Json.decodeText("{\"type\":\"hologram\"}", ChatEvent.class, "test");
        assertEquals("event hologram|true", ReadmeExamples.models(other));
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, COMPANY);
            assertEquals("Ромашка", ReadmeExamples.async(client).get(20, TimeUnit.SECONDS));
            server.enqueueJson(202, "{\"id\":\"run_1\",\"state\":\"queued\",\"proposals\":[],\"steps\":[],\"tools\":[]}").enqueueJson(200, "{\"id\":\"run_1\",\"state\":\"done\",\"proposals\":[],\"steps\":[],\"tools\":[]}");
            WaitOptions fast = WaitOptions.builder().initialInterval(Duration.ofMillis(10)).timeout(Duration.ofSeconds(20)).build();
            assertEquals("done", ReadmeExamples.waiting(client, fast));
        }
    }

    @Test
    void theReadmeShowsTheCompiledExamples() throws Exception {
        String readme = new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8);
        String source = new String(Files.readAllBytes(Paths.get("examples", "kz", "tungo", "examples", "ReadmeExamples.java")),
            StandardCharsets.UTF_8);
        int regions = 0;
        int from = 0;
        while (true) {
            int open = source.indexOf("// [", from);
            if (open < 0) {
                break;
            }
            int nameEnd = source.indexOf(']', open);
            String name = source.substring(open + 4, nameEnd);
            from = nameEnd;
            if (name.startsWith("/")) {
                continue;
            }
            int close = source.indexOf("// [/" + name + "]", nameEnd);
            assertTrue(close > 0, "region " + name + " is not closed");
            for (String line : source.substring(nameEnd + 1, close).split("\n")) {
                if (!line.trim().isEmpty()) {
                    assertTrue(readme.contains(line.trim()), "README lacks a line of region " + name + ": " + line.trim());
                }
            }
            regions++;
        }
        assertTrue(regions >= 12, "regions found: " + regions);
        assertTrue(readme.contains("<version>" + SdkVersion.VERSION + "</version>"), "the README shows the current version");
    }
}
