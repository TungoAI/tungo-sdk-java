package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.ConflictException;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.LimitException;
import kz.tungo.sdk.errors.TungoTimeoutException;
import kz.tungo.sdk.errors.UnavailableException;
import kz.tungo.sdk.internal.ClientConfig;
import kz.tungo.sdk.models.ChatDialogPatch;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.ChatTokenCountRequest;
import org.junit.jupiter.api.Test;

class ReliabilityTest {
    private static final String COMPANY = "{\"id\":\"co_1\",\"permissions\":[],\"name\":\"n\"}";
    private static final String TURN = "{\"answer\":\"ok\",\"dialog_id\":\"d\",\"turn_no\":1,\"citations\":[],\"errors\":[],\"followups\":[],\"proposals\":[],\"questions\":[]}";
    private static final String BUSY = TestSupport.envelope("unavailable_error", "busy", "Busy");
    private static final String UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private static ChatMessageRequest message() {
        return ChatMessageRequest.builder().message("m").build();
    }

    @Test
    void defaultsMatchTheContract() {
        assertEquals(2, ClientConfig.DEFAULT_MAX_RETRIES);
        assertEquals(Duration.ofSeconds(60), ClientConfig.DEFAULT_TIMEOUT);
        assertEquals(Duration.ofSeconds(600), ClientConfig.DEFAULT_LONG_TIMEOUT);
        assertEquals(Duration.ofSeconds(120), ClientConfig.DEFAULT_STREAM_IDLE_TIMEOUT);
    }

    @Test
    void aRedirectIsNeverFollowedAndTheKeyStaysHome() {
        try (MockServer other = new MockServer(); MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            other.always((request, exchange) -> exchange.json(200, COMPANY));
            server.always((request, exchange) -> exchange.respond(302, null, new byte[0], "Location", other.url() + "/v1/elsewhere"));
            ApiException plain = assertThrows(ApiException.class, () -> client.companies().get("co_1"));
            assertEquals(302, plain.statusCode());
            ApiException streamed = assertThrows(ApiException.class, () -> client.chat().createStream("co_1", message()));
            assertEquals(302, streamed.statusCode());
            assertEquals(2, server.requestCount());
            assertEquals(0, other.requestCount(), "the API key must not reach another host");

            HttpClient following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            assertThrows(ConfigException.class, () -> TestSupport.builder(server).httpClient(following));
        }
    }

    @Test
    void theHookTokenNeverAppearsInAnError() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String token = "SECRET_HOOK_TOKEN_123";
        try (TungoClient client = TungoClient.builder().apiKey(TestSupport.API_KEY)
            .baseUrl("http://127.0.0.1:" + port).maxRetries(0).build()) {
            ConnectionException e = assertThrows(ConnectionException.class,
                () -> client.sendHook(token, Map.of("a", 1), "hksec_1"));
            assertTrue(e.getMessage().contains("POST /hooks/{token}"), e.getMessage());
            StringWriter all = new StringWriter();
            e.printStackTrace(new PrintWriter(all));
            for (Throwable t = e; t != null; t = t.getCause()) {
                all.append(t.toString());
            }
            assertFalse(all.toString().contains(token), all.toString());
        }
    }

    @Test
    void retriesAGetTwiceByDefaultThenRaises() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(503, BUSY, "retry-after-ms", "1"));
            UnavailableException e = assertThrows(UnavailableException.class, () -> client.companies().get("co_1"));
            assertEquals(503, e.statusCode());
            assertEquals(3, server.requestCount());
        }
    }

    @Test
    void retriesEveryRetryableStatusAndNothingElse() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            for (int status : new int[] {408, 429, 500, 502, 503, 504}) {
                int before = server.requestCount();
                server.enqueueJson(status, BUSY, "retry-after-ms", "1").enqueueJson(200, COMPANY);
                assertEquals("co_1", client.companies().get("co_1").id(), "status " + status);
                assertEquals(before + 2, server.requestCount(), "status " + status);
            }
            for (int status : new int[] {400, 401, 403, 404, 409, 410, 422, 501}) {
                int before = server.requestCount();
                server.enqueueJson(status, BUSY, "retry-after-ms", "1");
                assertThrows(ApiException.class, () -> client.companies().get("co_1"), "status " + status);
                assertEquals(before + 1, server.requestCount(), "status " + status);
            }
        }
    }

    @Test
    void maxRetriesIsConfigurablePerClientAndPerRequest() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(1).build()) {
            server.always((request, exchange) -> exchange.json(503, BUSY, "retry-after-ms", "1"));
            assertThrows(UnavailableException.class, () -> client.companies().get("co_1"));
            assertEquals(2, server.requestCount());
            assertThrows(UnavailableException.class,
                () -> client.companies().get("co_1", RequestOptions.builder().maxRetries(0).build()));
            assertEquals(3, server.requestCount());
            assertThrows(UnavailableException.class,
                () -> client.companies().get("co_1", RequestOptions.builder().maxRetries(3).build()));
            assertEquals(7, server.requestCount());
        }
    }

    @Test
    void aPostOrPatchWithoutAnIdempotencyKeyIsNeverRetried() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(503, BUSY, "retry-after-ms", "1"));
            assertThrows(UnavailableException.class,
                () -> client.chat().countTokens("co_1", ChatTokenCountRequest.builder().message("m").build()));
            assertEquals(1, server.requestCount());
            assertNull(server.request(0).header("idempotency-key"));
            assertThrows(UnavailableException.class,
                () -> client.chat().update("co_1", "d1", ChatDialogPatch.builder().title("t").build()));
            assertEquals(2, server.requestCount());

            server.always((request, exchange) -> exchange.abort());
            assertThrows(ConnectionException.class,
                () -> client.chat().countTokens("co_1", ChatTokenCountRequest.builder().message("m").build()));
            assertEquals(3, server.requestCount());
        }
    }

    @Test
    void anIdempotencyKeyIsGeneratedForDeclaredOperationsAndReusedAcrossAttempts() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(503, BUSY, "retry-after-ms", "1").enqueueJson(503, BUSY, "retry-after-ms", "1").enqueueJson(200, TURN);
            assertEquals("ok", client.chat().create("co_1", message()).answer());
            assertEquals(3, server.requestCount());
            String key = server.request(0).header("idempotency-key");
            assertNotNull(key);
            assertTrue(key.matches(UUID_V4), key);
            assertEquals(key, server.request(1).header("idempotency-key"));
            assertEquals(key, server.request(2).header("idempotency-key"));

            server.enqueueJson(200, TURN);
            client.chat().create("co_1", message());
            assertFalse(key.equals(server.request(3).header("idempotency-key")), "a new call gets a new key");

            server.enqueueJson(200, TURN);
            client.chat().create("co_1", message(), RequestOptions.builder().idempotencyKey("my-key-1").build());
            assertEquals("my-key-1", server.request(4).header("idempotency-key"));
        }
    }

    @Test
    void aCallerSuppliedKeyDoesNotMakeAnUndeclaredPostRetryable() {
        // count-tokens declares no Idempotency-Key: the API ignores a key there, so the SDK does
        // not send it and a lost answer is not followed by a second, billed request.
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(503, BUSY, "retry-after-ms", "1").enqueueJson(200, "{\"input_tokens\":3}");
            assertThrows(UnavailableException.class, () -> client.chat().countTokens("co_1",
                ChatTokenCountRequest.builder().message("m").build(),
                RequestOptions.builder().idempotencyKey("k-7").header("Idempotency-Key", "k-8").build()));
            assertEquals(1, server.requestCount());
            assertNull(server.request(0).header("idempotency-key"));
        }
    }

    @Test
    void backsOffBetweenAttemptsWhenTheServerGivesNoDelay() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(503, BUSY).enqueueJson(200, COMPANY);
            long start = System.nanoTime();
            client.companies().get("co_1");
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 370, "first backoff is 0.375-0.5 s, was " + millis + " ms");
        }
    }

    @Test
    void honoursRetryAfterAndRetryAfterMs() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(429, BUSY, "retry-after-ms", "150", "Retry-After", "30").enqueueJson(200, COMPANY);
            long start = System.nanoTime();
            client.companies().get("co_1");
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 150 && millis < 10_000, "slept " + millis + " ms");
        }
    }

    @Test
    void aServerDelayAboveSixtySecondsIsNotSlept() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(429,
                TestSupport.envelope("limit_error", "rate_limited", "Slow down"), "Retry-After", "120"));
            long start = System.nanoTime();
            LimitException e = assertThrows(LimitException.class, () -> client.companies().get("co_1"));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
            assertEquals(1, server.requestCount());
            assertEquals(120.0, e.retryAfterSeconds().orElseThrow(), 0.0);
            assertEquals(Duration.ofSeconds(120), e.retryAfter().orElseThrow());
        }
    }

    @Test
    void connectionErrorsAreRetriedAndKeepTheirCause() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.abort()).enqueueJson(200, COMPANY);
            assertEquals("co_1", client.companies().get("co_1").id());
            assertEquals(2, server.requestCount());

            server.always((request, exchange) -> exchange.abort());
            ConnectionException e = assertThrows(ConnectionException.class,
                () -> client.companies().get("co_1", RequestOptions.builder().maxRetries(0).build()));
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof java.io.IOException, String.valueOf(e.getCause()));
            assertTrue(e.getMessage().contains("GET /v1/companies/{co}"), e.getMessage());
        }
    }

    @Test
    void theTimeoutCoversWaitingForTheResponse() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.clientClosed(10_000));
            long start = System.nanoTime();
            TungoTimeoutException e = assertThrows(TungoTimeoutException.class, () -> client.companies().get("co_1",
                RequestOptions.builder().timeout(Duration.ofMillis(300)).maxRetries(0).build()));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 250 && millis < 8_000, "took " + millis + " ms");
            assertNotNull(e.getCause());
            assertTrue(e.getMessage().contains("300 ms"), e.getMessage());
        }
    }

    @Test
    void theTimeoutCoversReadingTheBody() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).timeout(Duration.ofMillis(400)).maxRetries(0).build()) {
            server.always((request, exchange) -> {
                exchange.head(200, "application/json");
                exchange.write("{\"id\":");
                exchange.clientClosed(10_000);
            });
            long start = System.nanoTime();
            assertThrows(TungoTimeoutException.class, () -> client.companies().get("co_1"));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 350 && millis < 8_000, "took " + millis + " ms");
        }
    }

    @Test
    void timeoutsAreRetriedForSafeRequests() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).timeout(Duration.ofMillis(300)).build()) {
            server.enqueue((request, exchange) -> exchange.clientClosed(10_000)).enqueueJson(200, COMPANY);
            assertEquals("co_1", client.companies().get("co_1").id());
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    void aSuccessWithABrokenBodyIsADecodeErrorAndIsNotRetried() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.respond(200, "text/html", "<html>oops</html>".getBytes(), "x-request-id", "req_9"));
            DecodeException html = assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            assertEquals(200, html.statusCode());
            assertEquals("req_9", html.requestId().orElseThrow());
            assertEquals("<html>oops</html>", html.rawBody().orElseThrow());
            assertNotNull(html.getCause());
            assertEquals(1, server.requestCount());

            server.enqueueJson(200, "{\"id\":\"co_1\",\"na");
            assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            server.enqueueJson(200, "");
            assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            server.enqueueJson(200, "null");
            assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            server.enqueueJson(200, "{\"id\":\"co_1\",\"permissions\":[]} trailing");
            assertThrows(DecodeException.class, () -> client.companies().get("co_1"));
            assertEquals(5, server.requestCount());
        }
    }

    @Test
    void emptyResultsNeedNoBody() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.respond(204, null, new byte[0], "x-request-id", "req_d"));
            ApiResponse<Void> response = client.webhooks().deleteEndpointWithResponse("ws_1", "ep_1", null);
            assertEquals(204, response.statusCode());
            assertEquals("req_d", response.requestId().orElseThrow());
            assertEquals("DELETE", server.request(0).method);
        }
    }

    @Test
    void anInterruptAbortsARunningRequestAndKeepsTheFlag() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            CountDownLatch arrived = new CountDownLatch(1);
            server.always((request, exchange) -> {
                arrived.countDown();
                exchange.clientClosed(10_000);
            });
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> flag = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    client.companies().get("co_1");
                } catch (Throwable t) {
                    thrown.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            assertTrue(arrived.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive());
            assertTrue(thrown.get() instanceof InterruptedRequestException, String.valueOf(thrown.get()));
            assertTrue(flag.get(), "the interrupt flag must stay set");
            assertEquals(1, server.requestCount(), "an interrupted request is not retried");
        }
    }

    @Test
    void anInterruptAbortsTheRetryDelay() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            CountDownLatch answered = new CountDownLatch(1);
            server.always((request, exchange) -> {
                exchange.json(503, BUSY, "Retry-After", "30");
                answered.countDown();
            });
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> flag = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    client.companies().get("co_1");
                } catch (Throwable t) {
                    thrown.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                }
            });
            long start = System.nanoTime();
            caller.start();
            assertTrue(answered.await(10, TimeUnit.SECONDS));
            Thread.sleep(200);
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000);
            assertTrue(thrown.get() instanceof InterruptedRequestException, String.valueOf(thrown.get()));
            assertTrue(flag.get());
            assertEquals(1, server.requestCount());
            assertTrue(thrown.get().getSuppressed().length == 1 && thrown.get().getSuppressed()[0] instanceof UnavailableException,
                "the error that was being retried is attached");
        }
    }

    @Test
    void anAlreadyInterruptedThreadSendsNothing() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedRequestException.class, () -> client.companies().get("co_1"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void aNonRetryableErrorOnARetryableRequestSurfacesAtOnce() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(409, TestSupport.envelope("conflict_error", "turn_running", "Busy"));
            assertThrows(ConflictException.class, () -> client.chat().create("co_1", message()));
            assertEquals(1, server.requestCount());
        }
    }
}
