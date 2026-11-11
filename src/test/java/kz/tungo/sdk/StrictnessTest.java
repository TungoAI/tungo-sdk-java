package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.PaginationException;
import kz.tungo.sdk.errors.WebhookVerificationException;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.internal.RetryPolicy;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.Company;
import kz.tungo.sdk.models.Money;
import kz.tungo.sdk.models.ProposalListItem;
import kz.tungo.sdk.models.WebhookEvent;
import org.junit.jupiter.api.Test;

/**
 * A reply that is cut off, mistyped or incomplete must fail loudly instead of looking like a
 * valid, finished answer.
 */
class StrictnessTest {
    private static final String TEXT = "event: text\ndata: {\"type\":\"text\",\"delta\":\"Часть ответа\"}\n\n";
    private static final String DONE = "event: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\"}\n\n";
    private static final String COMPANY = "{\"id\":\"co_1\",\"name\":\"n\",\"permissions\":[]}";
    private static final String SECRET = "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static ChatMessageRequest message() {
        return ChatMessageRequest.builder().message("m").build();
    }

    @Test
    void aChatStreamThatEndsWithoutDoneIsNotAFinishedTurn() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                exchange.sse("x-request-id", "req_cut");
                exchange.write(TEXT);
            });
            DecodeException e = assertThrows(DecodeException.class,
                () -> client.chat().createStream("co_1", message()).accumulate());
            assertTrue(e.getMessage().contains("ended before completion"), e.getMessage());
            assertEquals("req_cut", e.requestId().orElseThrow());

            // Reading the raw events simply ends.
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                ChatStreamAccumulator acc = new ChatStreamAccumulator();
                for (ChatEvent event : stream) {
                    acc.add(event);
                }
                assertEquals(1, acc.eventCount());
                assertFalse(acc.isComplete());
                assertEquals("Часть ответа", acc.textSoFar());
                DecodeException manual = assertThrows(DecodeException.class, () -> acc.result(null, null));
                assertTrue(manual.getMessage().contains("ended before completion"), manual.getMessage());
            }
        }
    }

    @Test
    void aCompleteChatStreamStillAccumulates() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT + DONE);
            });
            assertEquals("Часть ответа", client.chat().createStream("co_1", message()).accumulate().text());
        }
    }

    @Test
    void aSuccessReplyThatIsNotAnEventStreamIsADecodeError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.respond(200, "text/html", "<html>proxy</html>".getBytes(StandardCharsets.UTF_8)));
            DecodeException html = assertThrows(DecodeException.class, () -> client.chat().createStream("co_1", message()));
            assertEquals(200, html.statusCode());
            assertTrue(html.getMessage().contains("text/html"), html.getMessage());
            assertTrue(html.rawBody().orElse("").contains("proxy"));

            server.enqueue((request, exchange) -> exchange.respond(200, null, new byte[0]));
            DecodeException untyped = assertThrows(DecodeException.class, () -> client.feed().listEvents("co_1"));
            assertTrue(untyped.getMessage().contains("no Content-Type"), untyped.getMessage());

            server.enqueue((request, exchange) -> exchange.respond(200, "text/plain", (TEXT + DONE).getBytes(StandardCharsets.UTF_8)));
            assertThrows(DecodeException.class, () -> client.chat().createStream("co_1", message()));

            // The media type is compared without its parameters and without regard to case.
            server.enqueue((request, exchange) -> {
                exchange.head(200, "Text/Event-Stream; charset=utf-8");
                exchange.write(TEXT + DONE);
            });
            assertEquals(2, client.chat().createStream("co_1", message()).accumulate().events().size());
        }
    }

    @Test
    void aZeroOrPastRetryAfterMeansTheNormalBackoff() {
        assertEquals(0.375, RetryPolicy.delaySeconds(0.0, 0, 0.0), 1e-9);
        assertEquals(0.75, RetryPolicy.delaySeconds(-3.0, 1, 0.0), 1e-9);
        assertEquals(0.375, RetryPolicy.delaySeconds(null, 0, 0.0), 1e-9);
        assertEquals(2.0, RetryPolicy.delaySeconds(2.0, 0, 0.0), 1e-9);
        assertEquals(0.001, RetryPolicy.delaySeconds(0.001, 3, 0.0), 1e-9);
        Double past = RetryPolicy.parseRetryAfter(
            TestSupport.headers(TestSupport.json("{\"Retry-After\":\"Mon, 09 Nov 2026 14:59:00 GMT\"}")),
            Instant.parse("2026-11-09T15:00:00Z"));
        assertNotNull(past);
        assertTrue(RetryPolicy.delaySeconds(past, 0, 0.0) >= 0.375, "a date in the past is not an immediate retry");

        for (String header : new String[] {"0", "Mon, 09 Nov 2020 14:59:00 GMT"}) {
            try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
                server.enqueueJson(503, TestSupport.envelope("unavailable_error", "busy", "Busy"), "Retry-After", header)
                    .enqueueJson(200, COMPANY);
                long start = System.nanoTime();
                assertEquals("co_1", client.companies().get("co_1").id());
                long millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(millis >= 300, "Retry-After: " + header + " retried after " + millis + " ms");
                assertEquals(2, server.requestCount());
            }
        }
    }

    @Test
    void aCursorCycleStopsThePagination() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                String query = request.query();
                String next = query.contains("cursor=A") ? "B" : "A";
                String id = query.contains("cursor=A") ? "p2" : query.contains("cursor=B") ? "p3" : "p1";
                exchange.json(200, "{\"data\":[{\"id\":\"" + id + "\"}],\"counts\":{},\"next_cursor\":\"" + next + "\"}");
            });
            List<String> seen = new ArrayList<>();
            PaginationException e = assertThrows(PaginationException.class, () -> {
                for (ProposalListItem item : client.proposals().listAll("co_1")) {
                    seen.add(item.id());
                }
            });
            assertTrue(e.getMessage().contains("A"), e.getMessage());
            assertEquals(List.of("p1", "p2", "p3"), seen);
            assertEquals(3, server.requestCount(), "the cycle A, B, A is cut when A comes back");
        }
    }

    @Test
    void aListWithoutItsDataIsADecodeError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{}", "x-request-id", "req_empty");
            DecodeException list = assertThrows(DecodeException.class, () -> client.companies().list());
            assertTrue(list.getMessage().contains("data"), list.getMessage());
            assertEquals("req_empty", list.requestId().orElseThrow());

            server.enqueueJson(200, "{\"counts\":{},\"next_cursor\":null}");
            assertThrows(DecodeException.class, () -> client.proposals().list("co_1"));
            server.enqueueJson(200, "{\"data\":null,\"counts\":{},\"next_cursor\":null}");
            assertThrows(DecodeException.class, () -> client.proposals().listAll("co_1").iterator().hasNext());

            server.enqueueJson(200, "{\"data\":[]}");
            assertTrue(client.companies().list().data().isEmpty());
        }
    }

    @Test
    void aRequiredCollectionIsNeverNull() {
        DecodeException e = assertThrows(DecodeException.class,
            () -> Json.decodeText("{\"id\":\"co_1\",\"name\":\"n\"}", Company.class, "test"));
        assertTrue(e.getMessage().contains("permissions"), e.getMessage());
        assertThrows(DecodeException.class,
            () -> Json.decodeText("{\"id\":\"co_1\",\"permissions\":null}", Company.class, "test"));
        assertTrue(Json.decodeText(COMPANY, Company.class, "test").permissions().isEmpty());
    }

    @Test
    void scalarsAreNotConvertedBetweenJsonTypes() {
        Money money = Json.decodeText("{\"amount\":12,\"amount_tiyn\":1200,\"formatted\":\"12 ₸\"}", Money.class, "test");
        assertEquals(1200L, money.amountTiyn().longValue());
        assertEquals(12.0, money.amount().doubleValue(), 0.0);
        for (String bad : new String[] {
            "{\"amount\":12,\"amount_tiyn\":12.7,\"formatted\":\"x\"}",
            "{\"amount\":12,\"amount_tiyn\":\"12\",\"formatted\":\"x\"}",
            "{\"amount\":12,\"amount_tiyn\":true,\"formatted\":\"x\"}",
            "{\"amount\":\"12\",\"amount_tiyn\":12,\"formatted\":\"x\"}",
            "{\"amount\":12,\"amount_tiyn\":12,\"formatted\":5}",
            "{\"amount\":12,\"amount_tiyn\":12,\"formatted\":true}",
        }) {
            assertThrows(DecodeException.class, () -> Json.decodeText(bad, Money.class, "test"), bad);
        }
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"id\":5,\"name\":\"n\",\"permissions\":[]}");
            assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            assertEquals(1, server.requestCount(), "a decode error is not retried");
        }
    }

    @Test
    void theAsyncPoolIsBoundedAndItsThreadsAreDaemons() throws Exception {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).asyncMaxThreads(2).build()) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            server.always((request, exchange) -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                release.await(20, TimeUnit.SECONDS);
                running.decrementAndGet();
                exchange.json(200, COMPANY);
            });
            List<CompletableFuture<Company>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(client.async().companies().get("co_1"));
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (server.requestCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(300);
            assertEquals(2, server.requestCount(), "only asyncMaxThreads calls run at once; the rest wait");
            int poolThreads = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith("tungo-sdk-async-")) {
                    poolThreads++;
                    assertTrue(thread.isDaemon(), thread.getName());
                }
            }
            assertTrue(poolThreads >= 2, "pool threads: " + poolThreads);
            release.countDown();
            for (CompletableFuture<Company> call : calls) {
                assertEquals("co_1", call.get(20, TimeUnit.SECONDS).id());
            }
            assertEquals(6, server.requestCount());
            assertEquals(2, peak.get());
        }
        try (MockServer server = new MockServer()) {
            assertThrows(ConfigException.class, () -> TestSupport.builder(server).asyncMaxThreads(0));
        }
    }

    @Test
    void aWebhookIsVerifiedFromTheBytesOfItsBody() {
        String body = "{\"id\":\"evt_1\",\"type\":\"proposal.written\",\"created_at\":\"2026-11-09T10:00:00Z\",\"data\":{\"kind\":\"Платёж\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        byte[] prefix = ("msg_1." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(bytes, 0, signed, prefix.length, bytes.length);
        byte[] mac = TungoWebhooks.hmacSha256(Base64.getDecoder().decode(SECRET.substring(6)), signed);
        Map<String, String> headers = new HashMap<>();
        headers.put("webhook-id", "msg_1");
        headers.put("webhook-timestamp", Long.toString(timestamp));
        headers.put("webhook-signature", "v1," + Base64.getEncoder().encodeToString(mac));

        WebhookEvent fromBytes = TungoWebhooks.verify(bytes, headers, SECRET);
        assertEquals("proposal.written", fromBytes.type());
        assertEquals(fromBytes, TungoWebhooks.verify(body, headers, SECRET));
        assertEquals(fromBytes, WebhookVerifier.of(SECRET).verify(bytes, headers::get));
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).webhookSecret(SECRET).build()) {
            assertEquals(fromBytes, client.verifyWebhook(bytes, headers));
        }

        byte[] tampered = bytes.clone();
        tampered[tampered.length - 3] ^= 1;
        assertEquals(WebhookVerificationException.Reason.SIGNATURE_MISMATCH,
            assertThrows(WebhookVerificationException.class, () -> TungoWebhooks.verify(tampered, headers, SECRET)).reason());
        assertEquals(WebhookVerificationException.Reason.MISSING_HEADER,
            assertThrows(WebhookVerificationException.class, () -> TungoWebhooks.verify((byte[]) null, headers, SECRET)).reason());
    }
}
