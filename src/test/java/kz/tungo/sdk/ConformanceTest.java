package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.ValidationException;
import kz.tungo.sdk.errors.WebhookVerificationException;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.internal.PathSegments;
import kz.tungo.sdk.internal.QueryString;
import kz.tungo.sdk.internal.RetryPolicy;
import kz.tungo.sdk.internal.SseParser;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.WebhookEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs every section of the shared conformance vectors (conformance/vectors.json). */
class ConformanceTest {
    private static JsonNode vectors;

    @BeforeAll
    static void load() {
        vectors = TestSupport.vectors();
        assertEquals(2, vectors.get("version").asInt());
    }

    private static List<JsonNode> cases(String... path) {
        JsonNode node = vectors;
        for (String key : path) {
            node = node.get(key);
            assertNotNull(node, "missing section " + String.join(".", path));
        }
        List<JsonNode> cases = TestSupport.list(node);
        assertFalse(cases.isEmpty(), "empty section " + String.join(".", path));
        return cases;
    }

    private static String name(JsonNode c) {
        return c.path("name").asText(c.toString());
    }

    @Test
    void webhookVerification() {
        assertEquals(300, vectors.get("webhook_verification").get("default_tolerance_seconds").asInt());
        for (JsonNode c : cases("webhook_verification", "cases")) {
            Map<String, String> headers = new HashMap<>();
            Iterator<String> names = c.get("headers").fieldNames();
            while (names.hasNext()) {
                String header = names.next();
                headers.put(header, c.get("headers").get(header).asText());
            }
            WebhookVerifier verifier = WebhookVerifier.of(c.get("secret").asText())
                .withClock(Clock.fixed(Instant.ofEpochSecond(c.get("now").asLong()), ZoneOffset.UTC));
            if (c.has("tolerance_seconds")) {
                verifier = verifier.withTolerance(Duration.ofSeconds(c.get("tolerance_seconds").asLong()));
            }
            WebhookVerifier v = verifier;
            String body = c.get("body").asText();
            JsonNode expect = c.get("expect");
            if (expect.get("valid").asBoolean()) {
                WebhookEvent event = v.verify(body, headers);
                assertEquals(expect.get("event_type").asText(), event.type(), name(c));
                // Only the deliberately incomplete vector may fall back to the Unknown variant.
                if (!c.has("contract_valid") || c.get("contract_valid").asBoolean()) {
                    assertFalse(event instanceof WebhookEvent.Unknown, name(c));
                }
            } else {
                WebhookVerificationException e = assertThrows(WebhookVerificationException.class, () -> v.verify(body, headers), name(c));
                assertEquals(expect.get("reason").asText(), e.reason().name().toLowerCase(Locale.ROOT), name(c));
            }
        }
    }

    @Test
    void hookSignature() {
        for (JsonNode c : cases("hook_signature", "sign")) {
            byte[] body = Base64.getDecoder().decode(c.get("body_base64").asText());
            String secret = c.get("secret").asText();
            if (c.get("expect").has("error")) {
                assertThrows(ValidationException.class, () -> TungoWebhooks.hookSignatureHex(body, secret), name(c));
                assertThrows(ValidationException.class, () -> TungoWebhooks.signHook(body, secret), name(c));
                continue;
            }
            assertEquals(c.get("expect").get("hex").asText(), TungoWebhooks.hookSignatureHex(body, secret), name(c));
            assertEquals(c.get("expect").get("header").asText(), TungoWebhooks.signHook(body, secret), name(c));
        }
        for (JsonNode c : cases("hook_signature", "verify")) {
            byte[] body = Base64.getDecoder().decode(c.get("body_base64").asText());
            assertEquals(c.get("expect").get("valid").asBoolean(),
                TungoWebhooks.verifyHookSignature(body, c.get("signature").asText(), c.get("secret").asText()), name(c));
        }
    }

    @Test
    void moneyToTiyn() {
        for (JsonNode c : cases("money", "to_tiyn")) {
            String input = c.get("input").asText();
            if (c.get("expect").has("error")) {
                assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(input), name(c));
            } else {
                assertEquals(Long.parseLong(c.get("expect").get("tiyn").asText()), TungoMoney.toTiyn(input), name(c));
                assertEquals(Long.parseLong(c.get("expect").get("tiyn").asText()),
                    TungoMoney.toTiyn(new java.math.BigDecimal(input)), name(c) + " (BigDecimal)");
            }
        }
    }

    @Test
    void moneyToTiynFromDouble() {
        for (JsonNode c : cases("money", "to_tiyn_float")) {
            double input;
            if (c.has("special")) {
                String special = c.get("special").asText();
                input = special.equals("NaN") ? Double.NaN
                    : special.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            } else {
                input = c.get("input").asDouble();
            }
            if (c.get("expect").has("error")) {
                assertThrows(ValidationException.class, () -> TungoMoney.toTiyn(input), name(c));
            } else {
                assertEquals(Long.parseLong(c.get("expect").get("tiyn").asText()), TungoMoney.toTiyn(input), name(c));
            }
        }
    }

    @Test
    void moneyFormatTiyn() {
        for (JsonNode c : cases("money", "format_tiyn")) {
            long tiyn = Long.parseLong(c.get("tiyn").asText());
            String text = c.has("currency") ? TungoMoney.formatTiyn(tiyn, c.get("currency").asText()) : TungoMoney.formatTiyn(tiyn);
            assertEquals(c.get("expect").get("text").asText(), text, name(c));
        }
    }

    @Test
    void binIin() {
        for (JsonNode c : cases("bin_iin")) {
            assertEquals(c.get("expect").get("valid").asBoolean(), TungoMoney.isValidBinIin(c.get("input").asText()), name(c));
        }
    }

    @Test
    void errorMapping() {
        for (JsonNode c : cases("errors", "cases")) {
            JsonNode expect = c.get("expect");
            ApiException e = ApiException.from(c.get("status").asInt(), TestSupport.headers(c.get("headers")), c.get("body").asText(),
                Clock.fixed(Instant.parse("2026-11-09T15:00:00Z"), ZoneOffset.UTC));
            String n = name(c);
            assertEquals(c.get("status").asInt(), e.statusCode(), n);
            assertEquals(expect.get("envelope").asBoolean(), e.isEnvelope(), n);
            assertSame(ErrorsTest.CLASSES.get(expect.get("class").asText()), e.getClass(), n);
            assertEquals(expect.get("type").asText(), e.type(), n);
            assertEquals(textOrNull(expect.get("code")), e.code().orElse(null), n);
            if (expect.has("message")) {
                assertEquals(textOrNull(expect.get("message")), e.errorMessage().orElse(null), n);
            }
            assertEquals(textOrNull(expect.get("request_id")), e.requestId().orElse(null), n);
            if (expect.get("retry_after").isNull()) {
                assertFalse(e.retryAfterSeconds().isPresent(), n);
            } else {
                assertEquals(expect.get("retry_after").asDouble(), e.retryAfterSeconds().orElseThrow(), 1e-9, n);
            }
            assertEquals(expect.get("fields"), Json.toTree(e.fields()), n);
            assertEquals(expect.get("extra"), Json.toTree(e.extra()), n);
            assertEquals(c.get("body").asText(), e.rawBody(), n);
            assertNotNull(e.getMessage(), n);
        }
    }

    @Test
    void errorStatusFallback() {
        for (JsonNode c : cases("errors", "status_fallback")) {
            assertEquals(c.get("expect").get("type").asText(), ApiException.statusFallback(c.get("status").asInt()), c.toString());
        }
    }

    @Test
    void retryAfter() {
        assertEquals(vectors.get("retry_after").get("max_sleep_seconds").asDouble(), RetryPolicy.MAX_RETRY_AFTER_SECONDS, 0.0);
        for (JsonNode c : cases("retry_after", "cases")) {
            Double seconds = RetryPolicy.parseRetryAfter(TestSupport.headers(c.get("headers")), Instant.parse(c.get("now").asText()));
            JsonNode expect = c.get("expect");
            if (expect.get("seconds").isNull() || expect.get("seconds").asDouble() == 0.0) {
                // "No usable delay": a zero delay and a date in the past both mean the computed backoff.
                assertTrue(seconds == null || seconds == 0.0, name(c));
                assertTrue(RetryPolicy.delaySeconds(seconds, 0, 0.0) >= RetryPolicy.BACKOFF_BASE_SECONDS * RetryPolicy.JITTER_MIN, name(c));
            } else {
                assertNotNull(seconds, name(c));
                assertEquals(expect.get("seconds").asDouble(), seconds, 1e-9, name(c));
            }
            boolean sleep = seconds == null || seconds <= RetryPolicy.MAX_RETRY_AFTER_SECONDS;
            assertEquals(expect.get("sleep").asBoolean(), sleep, name(c));
        }
    }

    @Test
    void backoff() {
        JsonNode section = vectors.get("backoff");
        assertEquals(section.get("jitter_min").asDouble(), RetryPolicy.JITTER_MIN, 0.0);
        assertEquals(section.get("jitter_max").asDouble(), RetryPolicy.JITTER_MAX, 0.0);
        assertEquals(section.get("max_seconds").asDouble(), RetryPolicy.BACKOFF_MAX_SECONDS, 0.0);
        for (JsonNode c : cases("backoff", "cases")) {
            int attempt = c.get("attempt").asInt();
            double min = c.get("expect").get("min_seconds").asDouble();
            double max = c.get("expect").get("max_seconds").asDouble();
            assertEquals(min, RetryPolicy.backoffSeconds(attempt, 0.0), 1e-9, name(c));
            assertEquals(max, RetryPolicy.backoffSeconds(attempt, 1.0), 1e-9, name(c));
            for (int i = 0; i < 50; i++) {
                double value = RetryPolicy.backoffSeconds(attempt);
                assertTrue(value >= min - 1e-9 && value <= max + 1e-9, name(c) + ": " + value);
            }
        }
    }

    @Test
    void retryPolicy() {
        for (JsonNode c : cases("retry_policy")) {
            boolean safe = RetryPolicy.isRetrySafe(c.get("method").asText(), c.get("idempotency_key").asBoolean());
            boolean retryableCause = c.get("cause").isNumber() ? RetryPolicy.isRetryableStatus(c.get("cause").asInt()) : true;
            assertEquals(c.get("expect").get("retry").asBoolean(), safe && retryableCause, name(c));
        }
    }

    @Test
    void pathSegment() {
        for (JsonNode c : cases("path_segment")) {
            String input = c.get("input").asText();
            if (c.get("expect").has("error")) {
                assertThrows(ValidationException.class, () -> PathSegments.encode("id", input), name(c));
            } else {
                assertEquals(c.get("expect").get("encoded").asText(), PathSegments.encode("id", input), name(c));
            }
        }
    }

    @Test
    void query() {
        for (JsonNode c : cases("query")) {
            QueryString query = new QueryString();
            for (JsonNode p : c.get("params")) {
                JsonNode value = p.get("value");
                String type = p.get("type").asText();
                Object typed;
                if (value == null || value.isNull()) {
                    typed = null;
                } else if (type.equals("boolean")) {
                    typed = value.asBoolean();
                } else if (type.equals("integer")) {
                    typed = Long.parseLong(value.asText());
                } else if (type.equals("date-time")) {
                    typed = java.time.OffsetDateTime.parse(value.asText());
                } else if (type.equals("array")) {
                    List<String> items = new ArrayList<>();
                    value.forEach(item -> items.add(item.asText()));
                    typed = items;
                } else {
                    typed = value.asText();
                }
                query.add(p.get("name").asText(), typed);
            }
            JsonNode expect = c.get("expect");
            if (expect.has("any_of")) {
                List<String> allowed = new ArrayList<>();
                expect.get("any_of").forEach(item -> allowed.add(item.asText()));
                assertTrue(allowed.contains(query.toString()), name(c) + ": " + query);
            } else {
                assertEquals(expect.get("query").asText(), query.toString(), name(c));
            }
            assertEquals(query.toString().isEmpty(), query.isEmpty(), name(c));
        }
    }

    private static void assertEvents(JsonNode expected, List<ServerSentEvent> actual, String name) {
        assertEquals(expected.size(), actual.size(), name);
        for (int i = 0; i < actual.size(); i++) {
            JsonNode e = expected.get(i);
            ServerSentEvent a = actual.get(i);
            assertEquals(e.get("event").asText(), a.event(), name);
            assertEquals(e.get("data").asText(), a.data(), name);
            assertEquals(textOrNull(e.get("id")), a.id().orElse(null), name);
            if (e.has("retry")) {
                assertEquals(e.get("retry").isNull() ? null : (Integer) e.get("retry").asInt(), a.retry().orElse(null), name);
            }
        }
    }

    @Test
    void sse() {
        for (JsonNode c : cases("sse")) {
            byte[] raw = Base64.getDecoder().decode(c.get("raw_base64").asText());
            JsonNode expected = c.get("expect").get("events");

            List<ServerSentEvent> whole = new ArrayList<>();
            new SseParser().feed(raw, whole::add);
            assertEvents(expected, whole, name(c) + " (one chunk)");

            List<ServerSentEvent> bytewise = new ArrayList<>();
            SseParser parser = new SseParser();
            for (int i = 0; i < raw.length; i++) {
                parser.feed(raw, i, 1, bytewise::add);
            }
            assertEvents(expected, bytewise, name(c) + " (byte by byte)");

            if (c.has("chunks_base64")) {
                List<ServerSentEvent> chunked = new ArrayList<>();
                SseParser p = new SseParser();
                for (JsonNode chunk : c.get("chunks_base64")) {
                    p.feed(Base64.getDecoder().decode(chunk.asText()), chunked::add);
                }
                assertEvents(expected, chunked, name(c) + " (given chunks)");
            }
        }
    }

    @Test
    void chatStreamReplayThatIsNotAReplayIsADecodeError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            int checked = 0;
            for (JsonNode c : cases("chat_stream_replay")) {
                if (!c.path("expect").path("decode_error").asBoolean(false)) {
                    continue;
                }
                byte[] raw = c.get("body").toString().getBytes(StandardCharsets.UTF_8);
                server.enqueue((request, exchange) -> {
                    exchange.head(c.get("status").asInt(), c.get("headers").get("Content-Type").asText(),
                        TestSupport.headerPairs(c.get("headers")));
                    exchange.write(raw);
                });
                DecodeException e = assertThrows(DecodeException.class,
                    () -> client.chat().createStream("co_1", ChatMessageRequest.builder().message("m").build()), name(c));
                assertEquals(c.get("status").asInt(), e.statusCode(), name(c));
                checked++;
            }
            assertTrue(checked > 0);
        }
    }

    @Test
    void chatStream() {
        // A turn repeated with the same Idempotency-Key after it finished is answered with its
        // stored JSON result (chat_stream_replay): the stream must yield the same turn.
        List<JsonNode> all = new ArrayList<>(cases("chat_stream"));
        for (JsonNode c : cases("chat_stream_replay")) {
            if (!c.path("expect").path("decode_error").asBoolean(false)) {
                all.add(c);
            }
        }
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            for (JsonNode c : all) {
                String n = name(c);
                byte[] raw = (c.has("raw") ? c.get("raw").asText() : c.get("body").toString()).getBytes(StandardCharsets.UTF_8);
                server.enqueue((request, exchange) -> {
                    exchange.head(c.get("status").asInt(), c.get("headers").get("Content-Type").asText(),
                        TestSupport.headerPairs(c.get("headers")));
                    exchange.write(raw);
                });
                JsonNode expect = c.get("expect");
                ChatStreamAccumulator acc = new ChatStreamAccumulator();
                List<String> types = new ArrayList<>();
                ChatStreamResult result;
                Optional<ApiException> error;
                try (ChatStream stream = client.chat().createStream("co_1", ChatMessageRequest.builder().message("m").build())) {
                    for (ChatEvent event : stream) {
                        acc.add(event);
                        types.add(event.type());
                    }
                    if (!types.contains("done")) {
                        // The stream was cut off: the raw events and the text so far are there, but
                        // the turn has no result.
                        assertFalse(acc.isComplete(), n);
                        assertEquals(expect.get("event_count").asInt(), acc.eventCount(), n);
                        assertEquals(expect.get("text").asText(), acc.textSoFar(), n);
                        String dialogId = stream.dialogId().orElse(null);
                        DecodeException cut = assertThrows(DecodeException.class, () -> acc.result(dialogId, null), n);
                        assertTrue(cut.getMessage().contains("ended before completion"), n);
                        continue;
                    }
                    result = acc.result(stream.dialogId().orElse(null), stream.turnNo().orElse(null));
                    error = acc.errorAsException(stream.response().statusCode(), stream.response().headers());
                }
                assertEquals(textOrNull(expect.get("dialog_id")), result.dialogId().orElse(null), n);
                assertEquals(expect.get("turn_no").isNull() ? null : (Long) expect.get("turn_no").asLong(), result.turnNo().orElse(null), n);
                assertEquals(expect.get("text").asText(), result.text(), n);
                assertEquals(textOrNull(expect.get("stop_reason")), result.stopReason().orElse(null), n);
                assertEquals(expect.get("event_count").asInt(), result.events().size(), n);
                assertEquals(expect.get("cost_tiyn").isNull() ? null : (Long) expect.get("cost_tiyn").asLong(),
                    result.costTiyn().orElse(null), n);
                assertEquals(textOrNull(expect.get("choice_id")), result.choiceId().orElse(null), n);
                if (expect.get("usage").isNull()) {
                    assertFalse(result.usage().isPresent(), n);
                } else {
                    JsonNode usage = Json.toTree(result.usage().orElseThrow());
                    Iterator<String> keys = expect.get("usage").fieldNames();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        assertEquals(expect.get("usage").get(key).asLong(), usage.get(key).asLong(), n + " usage." + key);
                    }
                }
                if (expect.has("event_types")) {
                    List<String> expectedTypes = new ArrayList<>();
                    expect.get("event_types").forEach(t -> expectedTypes.add(t.asText()));
                    assertEquals(expectedTypes, types, n);
                }
                if (expect.get("error").isNull()) {
                    assertFalse(error.isPresent(), n);
                } else {
                    JsonNode ee = expect.get("error");
                    ApiException e = error.orElseThrow();
                    assertEquals(ee.get("type").asText(), e.type(), n);
                    assertEquals(textOrNull(ee.get("code")), e.code().orElse(null), n);
                    assertEquals(textOrNull(ee.get("message")), e.errorMessage().orElse(null), n);
                    assertEquals(textOrNull(ee.get("request_id")), e.requestId().orElse(null), n);
                    if (ee.has("extra")) {
                        assertEquals(ee.get("extra"), Json.toTree(e.extra()), n);
                    }
                }
            }
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
