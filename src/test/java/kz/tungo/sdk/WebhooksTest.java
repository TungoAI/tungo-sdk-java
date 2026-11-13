package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.tungo.sdk.errors.UnavailableException;
import kz.tungo.sdk.errors.ValidationException;
import kz.tungo.sdk.errors.WebhookVerificationException;
import kz.tungo.sdk.errors.WebhookVerificationException.Reason;
import kz.tungo.sdk.models.AutomationHookAccepted;
import kz.tungo.sdk.models.WebhookEvent;
import org.junit.jupiter.api.Test;

class WebhooksTest {
    private static final String SECRET = "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"proposal.written\",\"created_at\":\"2026-11-09T10:00:00Z\",\"data\":{}}";

    private static String sign(String id, long timestamp, String body) {
        byte[] key = Base64.getDecoder().decode(SECRET.substring(6));
        byte[] mac = TungoWebhooks.hmacSha256(key, (id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac);
    }

    private static Map<String, String> headers(long timestamp) {
        Map<String, String> headers = new HashMap<>();
        headers.put("webhook-id", "msg_1");
        headers.put("webhook-timestamp", Long.toString(timestamp));
        headers.put("webhook-signature", sign("msg_1", timestamp, BODY));
        return headers;
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    @Test
    void verifiesAgainstTheSystemClockAndReturnsTheTypedEvent() {
        WebhookEvent event = TungoWebhooks.verify(BODY, headers(now()), SECRET);
        assertEquals("proposal.written", event.type());
        assertFalse(event instanceof WebhookEvent.Unknown, event.getClass().getName());
    }

    @Test
    void theToleranceIsFiveMinutesInBothDirections() {
        assertEquals(Reason.TIMESTAMP_TOO_OLD, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, headers(now() - 400), SECRET)).reason());
        assertEquals(Reason.TIMESTAMP_TOO_NEW, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, headers(now() + 400), SECRET)).reason());
        TungoWebhooks.verify(BODY, headers(now() - 200), SECRET);
        TungoWebhooks.verify(BODY, headers(now() + 200), SECRET);

        Clock fixed = Clock.fixed(Instant.ofEpochSecond(1_793_974_568L), ZoneOffset.UTC);
        WebhookVerifier verifier = WebhookVerifier.of(SECRET).withClock(fixed);
        verifier.verify(BODY, headers(1_793_974_568L - 300));
        assertThrows(WebhookVerificationException.class, () -> verifier.verify(BODY, headers(1_793_974_568L - 301)));
        verifier.withTolerance(Duration.ofHours(1)).verify(BODY, headers(1_793_974_568L - 3000));
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertEquals(Reason.INVALID_TOLERANCE, assertThrows(WebhookVerificationException.class,
                () -> verifier.withTolerance(bad).verify(BODY, headers(1_793_974_568L))).reason());
        }
    }

    @Test
    void acceptsTheUsualHeaderContainersCaseInsensitively() {
        long ts = now();
        Map<String, List<String>> multi = new HashMap<>();
        multi.put("Svix-Id", List.of("msg_1"));
        multi.put("SVIX-TIMESTAMP", List.of(Long.toString(ts)));
        multi.put("svix-Signature", List.of("v1,AAAA " + sign("msg_1", ts, BODY) + " v2,zzz"));
        assertEquals("proposal.written", TungoWebhooks.verify(BODY, multi, SECRET).type());

        HttpHeaders http = HttpHeaders.of(multi, (a, b) -> true);
        assertEquals("proposal.written", WebhookVerifier.of(SECRET).verify(BODY, http).type());

        Map<String, String> plain = headers(ts);
        assertEquals("proposal.written", TungoWebhooks.verify(BODY, name -> plain.get(name.toLowerCase()), SECRET).type());
    }

    @Test
    void everyFailureIsATypedVerificationError() {
        long ts = now();
        Map<String, String> good = headers(ts);
        assertEquals(Reason.SIGNATURE_MISMATCH, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY + " ", good, SECRET)).reason());
        assertEquals(Reason.INVALID_SECRET, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, good, "")).reason());
        assertEquals(Reason.INVALID_SECRET, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, good, null)).reason());
        assertEquals(Reason.INVALID_SECRET, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, good, "whsec_***not base64***")).reason());
        Map<String, String> missing = new HashMap<>(good);
        missing.remove("webhook-signature");
        assertEquals(Reason.MISSING_HEADER, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, missing, SECRET)).reason());
        assertEquals(Reason.MISSING_HEADER, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, (Map<String, String>) null, SECRET)).reason());
        Map<String, String> malformed = new HashMap<>(good);
        malformed.put("webhook-timestamp", "12abc");
        assertEquals(Reason.MALFORMED_TIMESTAMP, assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(BODY, malformed, SECRET)).reason());

        String notAnEvent = "[1,2,3]";
        Map<String, String> signedGarbage = new HashMap<>(good);
        signedGarbage.put("webhook-signature", sign("msg_1", ts, notAnEvent));
        WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
            () -> TungoWebhooks.verify(notAnEvent, signedGarbage, SECRET));
        assertEquals(Reason.INVALID_PAYLOAD, e.reason());
        assertTrue(e.getCause() != null);
    }

    @Test
    void theClientUsesItsConfiguredWebhookSecret() {
        Map<String, String> env = new HashMap<>();
        env.put("TUNGO_WEBHOOK_SECRET", SECRET);
        try (TungoClient client = TungoClient.builder().environment(env::get).build()) {
            assertEquals("proposal.written", client.verifyWebhook(BODY, headers(now())).type());
            Map<String, String> good = headers(now());
            assertEquals("proposal.written", client.verifyWebhook(BODY, good::get).type());
        }
        try (TungoClient client = TungoClient.builder().environment(name -> null).build()) {
            assertEquals(Reason.INVALID_SECRET, assertThrows(WebhookVerificationException.class,
                () -> client.verifyWebhook(BODY, headers(now()))).reason());
        }
    }

    @Test
    void sendHookSignsTheExactBytesInTheHeader() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(202, "{\"accepted\":true,\"run_id\":\"run_1\",\"run_ids\":[]}"));
            Map<String, Object> payload = new HashMap<>();
            payload.put("note", "Оплата");
            AutomationHookAccepted accepted = client.sendHook("tok/1 2", payload, "hksec_1");
            assertTrue(accepted != null);
            MockServer.Request request = server.request(0);
            assertEquals("POST", request.method);
            assertEquals("/hooks/tok%2F1%202", request.target);
            assertEquals("application/json", request.header("content-type"));
            assertEquals("{\"note\":\"Оплата\"}", request.text());
            assertEquals("sha256=" + TungoWebhooks.hookSignatureHex(request.body, "hksec_1"), request.header("x-signature"));
            assertTrue(TungoWebhooks.verifyHookSignature(request.body, request.header("x-signature"), "hksec_1"));
            assertNull(request.header("x-api-key"));
            assertNull(request.header("x-event-id"));

            client.sendHook("tok", payload, null);
            assertNull(server.request(1).header("x-signature"), "no secret: no signature header");
        }
    }

    @Test
    void sendHookSupportsRawBodiesAndEventIdsAndMatchesTheResourceMethod() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(202, "{\"accepted\":true,\"run_ids\":[]}"));
            byte[] raw = new byte[] {0, (byte) 0xFF, (byte) 0xFE, 13, 10, (byte) 0x80, 127};
            HookRequest hook = HookRequest.bytes(raw, "application/octet-stream").secret("hksec_1").eventId("evt-77").build();
            client.sendHook("tok", hook);
            client.automations().postHook("tok", hook);
            for (int i = 0; i < 2; i++) {
                MockServer.Request request = server.request(i);
                assertEquals("/hooks/tok", request.path());
                assertEquals("application/octet-stream", request.header("content-type"));
                assertEquals(raw.length, request.body.length);
                assertEquals("evt-77", request.header("x-event-id"));
                // The API reads Idempotency-Key before X-Event-Id: the event id must be the key.
                assertEquals("evt-77", request.header("idempotency-key"));
                assertEquals(TungoWebhooks.signHook(raw, "hksec_1"), request.header("x-signature"));
                assertNull(request.header("x-api-key"));
            }
            client.sendHook("tok", HookRequest.text("<x/>", "application/xml").build());
            assertEquals("<x/>", server.request(2).text());
            assertEquals("application/xml", server.request(2).header("content-type"));
            client.sendHook("tok", hook, RequestOptions.builder().idempotencyKey("own").build());
            assertEquals("own", server.request(3).header("idempotency-key"), "a key the caller gives wins");
            assertEquals("evt-77", server.request(3).header("x-event-id"));
            assertFalse(hook.toString().contains("hksec_1"));
            assertThrows(ValidationException.class, () -> HookRequest.json("x").secret(""));
        }
    }

    @Test
    void aHookWithItsOwnSignatureHeaderIsSignedInThatHeader() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(202, "{\"accepted\":true,\"run_ids\":[]}"));
            HookRequest hook = HookRequest.json(Map.of("a", 1)).secret("hksec_1").signatureHeader("x-shop-signature").build();
            client.sendHook("tok", hook);
            client.automations().postHook("tok", hook);
            for (int i = 0; i < 2; i++) {
                MockServer.Request request = server.request(i);
                assertNull(request.header("x-signature"), "the hook reads only its own signature_header");
                assertTrue(TungoWebhooks.verifyHookSignature(request.body, request.header("x-shop-signature"), "hksec_1"));
            }
            assertEquals("X-Signature", HookRequest.json("x").build().signatureHeader());
            assertThrows(ValidationException.class, () -> HookRequest.json("x").signatureHeader("bad header"));
        }
    }

    @Test
    void sendHookFollowsTheRetryRules() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            String busy = TestSupport.envelope("unavailable_error", "busy", "Busy");
            server.enqueueJson(503, busy, "retry-after-ms", "1").enqueueJson(202, "{\"accepted\":true,\"run_ids\":[]}");
            client.sendHook("tok", Map.of("a", 1), "s");
            assertEquals(2, server.requestCount());
            assertEquals(server.request(0).header("idempotency-key"), server.request(1).header("idempotency-key"));
            assertEquals(server.request(0).header("x-signature"), server.request(1).header("x-signature"));

            server.always((request, exchange) -> exchange.json(503, busy, "retry-after-ms", "1"));
            assertThrows(UnavailableException.class, () -> client.sendHook("tok", Map.of("a", 1), "s"));
            assertEquals(5, server.requestCount());
        }
    }

    @Test
    void hookSignaturesAreVerifiedInConstantTimeForBothCases() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String hex = TungoWebhooks.hookSignatureHex(body, "s3cret");
        assertTrue(hex.matches("[0-9a-f]{64}"));
        assertTrue(TungoWebhooks.verifyHookSignature(body, hex, "s3cret"));
        assertTrue(TungoWebhooks.verifyHookSignature(body, hex.toUpperCase(), "s3cret"));
        assertTrue(TungoWebhooks.verifyHookSignature(body, "sha256=" + hex, "s3cret"));
        assertFalse(TungoWebhooks.verifyHookSignature(body, hex, "other"));
        assertFalse(TungoWebhooks.verifyHookSignature(body, "", "s3cret"));
        assertFalse(TungoWebhooks.verifyHookSignature(body, null, "s3cret"));
        assertFalse(TungoWebhooks.verifyHookSignature(body, hex, ""));
        assertThrows(ValidationException.class, () -> TungoWebhooks.hookSignatureHex(body, ""));
    }
}
