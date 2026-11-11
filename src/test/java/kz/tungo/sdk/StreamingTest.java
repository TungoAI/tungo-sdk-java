package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.LimitException;
import kz.tungo.sdk.errors.NotFoundException;
import kz.tungo.sdk.errors.TungoTimeoutException;
import kz.tungo.sdk.errors.UnavailableException;
import kz.tungo.sdk.internal.SseParser;
import kz.tungo.sdk.models.ChatAnswerRequest;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatEventDone;
import kz.tungo.sdk.models.ChatEventError;
import kz.tungo.sdk.models.ChatEventText;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.FeedItem;
import org.junit.jupiter.api.Test;

class StreamingTest {
    private static final String TEXT_1 = "id: 1\nevent: text\ndata: {\"type\":\"text\",\"delta\":\"Прив\"}\n\n";
    private static final String TEXT_2 = "id: 2\nevent: text\ndata: {\"type\":\"text\",\"delta\":\"ет\"}\n\n";
    private static final String DONE = "id: 3\nevent: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\",\"cost_tiyn\":185}\n\n";
    private static final String BUSY = TestSupport.envelope("unavailable_error", "busy", "Busy");

    private static ChatMessageRequest message() {
        return ChatMessageRequest.builder().message("m").build();
    }

    @Test
    void eventsAreDeliveredAsTheyArrive() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            CountDownLatch firstSeen = new CountDownLatch(1);
            AtomicBoolean secondSentAfterFirstWasSeen = new AtomicBoolean();
            server.enqueue((request, exchange) -> {
                exchange.sse("X-Dialog-Id", "dlg-1", "X-Turn-No", "3");
                exchange.write(TEXT_1);
                secondSentAfterFirstWasSeen.set(firstSeen.await(15, TimeUnit.SECONDS));
                exchange.write(TEXT_2 + DONE);
            });
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                assertEquals("dlg-1", stream.dialogId().orElseThrow());
                assertEquals(3L, stream.turnNo().orElseThrow().longValue());
                Iterator<ChatEvent> events = stream.iterator();
                ChatEvent first = events.next();
                assertEquals("Прив", ((ChatEventText) first).delta());
                assertEquals("1", stream.lastEventId().orElseThrow());
                firstSeen.countDown();
                assertEquals("ет", ((ChatEventText) events.next()).delta());
                assertTrue(events.next() instanceof ChatEventDone);
                assertFalse(events.hasNext());
                assertEquals("3", stream.lastEventId().orElseThrow());
            }
            assertTrue(secondSentAfterFirstWasSeen.get(), "the first event must arrive before the server sends the second");
            MockServer.Request request = server.request(0);
            assertEquals("text/event-stream", request.header("accept"));
            assertTrue(TestSupport.json(request.text()).get("stream").asBoolean(), request.text());
            assertNotNull(request.header("idempotency-key"));
            assertEquals(TestSupport.API_KEY, request.header("x-api-key"));
        }
    }

    @Test
    void theNonStreamingVariantAsksForJson() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"answer\":\"42\",\"dialog_id\":\"d\",\"turn_no\":1,\"citations\":[],\"errors\":[],\"proposals\":[],\"questions\":[]}");
            assertEquals("42", client.ask("co_1", "Сколько?").answer());
            MockServer.Request request = server.request(0);
            assertEquals("application/json", request.header("accept"));
            assertFalse(TestSupport.json(request.text()).path("stream").asBoolean(false));
            assertEquals("Сколько?", TestSupport.json(request.text()).get("message").asText());
        }
    }

    @Test
    void closingTheStreamClosesTheConnection() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            AtomicBoolean clientGone = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1);
                clientGone.set(exchange.clientClosed(15_000));
                finished.countDown();
            });
            try (ChatStream stream = client.askStream("co_1", "m")) {
                for (ChatEvent event : stream) {
                    assertTrue(event instanceof ChatEventText);
                    break;
                }
            }
            assertTrue(finished.await(20, TimeUnit.SECONDS));
            assertTrue(clientGone.get(), "an early break plus close() must close the connection");
        }
    }

    @Test
    void aClosedStreamYieldsNothingMore() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1);
                exchange.clientClosed(15_000);
            });
            ChatStream stream = client.chat().createStream("co_1", message());
            assertTrue(stream.iterator().hasNext());
            stream.close();
            stream.close();
            stream.iterator().next();
            assertFalse(stream.iterator().hasNext());
        }
    }

    @Test
    void pingsAreSkippedAndUnknownEventsSurvive() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(": keepalive\n\nevent: ping\ndata:\n\n" + TEXT_1
                    + "event: hologram\ndata: {\"type\":\"hologram\",\"x\":1}\n\n" + DONE + "event: text\ndata: {\"type\":\"text\"");
            });
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                List<String> types = stream.stream().map(ChatEvent::type).collect(Collectors.toList());
                assertEquals(List.of("text", "hologram", "done"), types);
            }
        }
    }

    @Test
    void accumulateBuildsTheAnswer() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse("X-Dialog-Id", "dlg-1", "X-Turn-No", "3");
                exchange.write(TEXT_1 + TEXT_2 + DONE);
            });
            ChatStreamResult result = client.chat().createStream("co_1", message()).accumulate();
            assertEquals("Привет", result.text());
            assertEquals("end_turn", result.stopReason().orElseThrow());
            assertEquals(185L, result.costTiyn().orElseThrow().longValue());
            assertEquals("dlg-1", result.dialogId().orElseThrow());
            assertEquals(3L, result.turnNo().orElseThrow().longValue());
            assertEquals(3, result.events().size());
        }
    }

    @Test
    void aMidStreamErrorIsATypedEventAndATypedExceptionInAccumulate() {
        String error = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"limit_error\",\"code\":\"budget\","
            + "\"message\":\"Дневной лимит исчерпан.\"},\"request_id\":\"req_s1\"}\n\n";
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1 + error + "event: done\ndata: {\"type\":\"done\",\"stop_reason\":\"budget\"}\n\n");
            });
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                List<ChatEvent> events = new ArrayList<>();
                stream.forEach(events::add);
                assertEquals(3, events.size());
                assertTrue(events.get(1) instanceof ChatEventError);
            }
            LimitException e = assertThrows(LimitException.class, () -> client.chat().createStream("co_1", message()).accumulate());
            assertEquals("budget", e.code().orElseThrow());
            assertEquals("req_s1", e.requestId().orElseThrow());
            assertEquals("Дневной лимит исчерпан.", e.errorMessage().orElseThrow());
        }
    }

    @Test
    void answerStreamSendsNoIdempotencyKeyAndIsNotRetried() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(503, BUSY, "retry-after-ms", "1"));
            ChatAnswerRequest body = ChatAnswerRequest.builder().choiceId("ch_1").answers(List.of()).build();
            assertThrows(UnavailableException.class, () -> client.chat().answerStream("co_1", "dlg 1", body));
            assertEquals(1, server.requestCount());
            assertNull(server.request(0).header("idempotency-key"));
            assertEquals("/v1/companies/co_1/chat/dlg%201/answer", server.request(0).path());
            assertTrue(TestSupport.json(server.request(0).text()).get("stream").asBoolean());
        }
    }

    @Test
    void streamRequestsAreRetriedBeforeTheFirstByteWithTheSameKey() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(503, BUSY, "retry-after-ms", "1").enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1 + DONE);
            });
            assertEquals("Прив", client.chat().createStream("co_1", message()).accumulate().text());
            assertEquals(2, server.requestCount());
            assertEquals(server.request(0).header("idempotency-key"), server.request(1).header("idempotency-key"));
        }
    }

    @Test
    void aFailedStreamRequestIsTheSameTypedApiError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(404, TestSupport.envelope("not_found_error", "dialog_not_found", "Нет"));
            NotFoundException e = assertThrows(NotFoundException.class, () -> client.chat().listEvents("co_1", "dlg_x"));
            assertEquals("dialog_not_found", e.code().orElseThrow());
            assertEquals("req_test_1", e.requestId().orElseThrow());
        }
    }

    @Test
    void aJsonBodyWhereAStreamWasExpectedIsADecodeError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"answer\":\"not a stream\"}");
            DecodeException e = assertThrows(DecodeException.class, () -> client.chat().createStream("co_1", message()));
            assertEquals(200, e.statusCode());
            assertTrue(e.rawBody().orElse("").contains("not a stream"));
        }
    }

    @Test
    void thePureGetStreamsWork() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1 + DONE);
            });
            try (ChatStream stream = client.chat().listEvents("co_1", "dlg_1")) {
                assertEquals(2, stream.stream().count());
            }
            assertEquals("GET", server.request(0).method);
            assertEquals("/v1/companies/co_1/chat/dlg_1/events", server.request(0).path());
            assertEquals("text/event-stream", server.request(0).header("accept"));
            assertNull(server.request(0).header("content-type"));

            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write("event: ping\ndata:\n\nid: f1\nevent: feed\ndata: {\"id\":\"fi_1\",\"cursor\":\"c1\",\"at\":\"2026-11-09T10:00:00Z\",\"actions\":[]}\n\n");
            });
            try (EventStream<FeedItem> feed = client.feed().listEvents("co_1")) {
                List<FeedItem> items = feed.stream().collect(Collectors.toList());
                assertEquals(1, items.size());
                assertEquals("c1", items.get(0).cursor());
                assertEquals("f1", feed.lastEventId().orElseThrow());
                assertEquals(200, feed.response().statusCode());
            }
            assertEquals("/v1/companies/co_1/feed/events", server.request(1).path());
            assertEquals("text/event-stream", server.request(1).header("accept"));

            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write("event: batch\ndata: {\"type\":\"batch\",\"done\":1,\"total\":2}\n\n" + DONE);
            });
            try (ChatStream batch = client.batches().listEvents("co_1", "b_1")) {
                assertEquals(List.of("batch", "done"), batch.stream().map(ChatEvent::type).collect(Collectors.toList()));
            }
            assertEquals("/v1/companies/co_1/batch/b_1/events", server.request(2).path());
        }
    }

    @Test
    void aFeedErrorEventIsRaisedAsATypedError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse("x-request-id", "req_feed");
                exchange.write("event: error\ndata: {\"error\":\"feed_failed\",\"message\":\"Лента недоступна\"}\n\n");
            });
            try (EventStream<FeedItem> feed = client.feed().listEvents("co_1")) {
                ApiException e = assertThrows(ApiException.class, () -> feed.iterator().hasNext());
                assertEquals("feed_failed", e.code().orElseThrow());
                assertEquals("Лента недоступна", e.errorMessage().orElseThrow());
            }
        }
    }

    @Test
    void theLastEventIdCanBeSentToResume() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> exchange.sse());
            try (ChatStream stream = client.chat().listEvents("co_1", "dlg_1", null,
                RequestOptions.builder().header("Last-Event-ID", "41").build())) {
                assertFalse(stream.iterator().hasNext());
            }
            assertEquals("41", server.request(0).header("last-event-id"));
        }
    }

    @Test
    void aSilentStreamTimesOutAfterTheIdleTimeout() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).streamIdleTimeout(Duration.ofMillis(400)).build()) {
            server.always((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1);
                exchange.clientClosed(15_000);
            });
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                Iterator<ChatEvent> events = stream.iterator();
                assertNotNull(events.next());
                long start = System.nanoTime();
                TungoTimeoutException e = assertThrows(TungoTimeoutException.class, events::hasNext);
                long millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(millis >= 300 && millis < 10_000, "took " + millis + " ms");
                assertTrue(e.getMessage().contains("400 ms"), e.getMessage());
            }
            try (ChatStream stream = client.chat().createStream("co_1", message(),
                RequestOptions.builder().streamIdleTimeout(Duration.ofMillis(200)).build())) {
                Iterator<ChatEvent> events = stream.iterator();
                events.next();
                TungoTimeoutException e = assertThrows(TungoTimeoutException.class, events::hasNext);
                assertTrue(e.getMessage().contains("200 ms"), e.getMessage());
            }
        }
    }

    @Test
    void aStreamHasNoTotalTimeout() {
        try (MockServer server = new MockServer();
            TungoClient client = TestSupport.builder(server).timeout(Duration.ofMillis(200)).build()) {
            server.enqueue((request, exchange) -> {
                exchange.sse();
                for (int i = 0; i < 6; i++) {
                    exchange.write(TEXT_1);
                    Thread.sleep(100);
                }
                exchange.write(DONE);
            });
            assertEquals(7, client.chat().createStream("co_1", message()).accumulate().events().size());
        }
    }

    @Test
    void aBrokenConnectionMidStreamIsAConnectionError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.respondChunkedStart("text/event-stream");
                exchange.writeChunk(TEXT_1);
                Thread.sleep(200);
                exchange.abort();
            });
            try (ChatStream stream = client.chat().createStream("co_1", message())) {
                Iterator<ChatEvent> events = stream.iterator();
                assertNotNull(events.next());
                ConnectionException e = assertThrows(ConnectionException.class, events::hasNext);
                assertNotNull(e.getCause());
            }
            assertEquals(1, server.requestCount(), "a stream is never retried after the first byte");
        }
    }

    @Test
    void anInterruptStopsAStreamRead() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse();
                exchange.write(TEXT_1);
                exchange.clientClosed(15_000);
            });
            CountDownLatch firstSeen = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> flag = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (ChatStream stream = client.chat().createStream("co_1", message())) {
                    for (ChatEvent event : stream) {
                        firstSeen.countDown();
                    }
                } catch (Throwable t) {
                    thrown.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                }
            });
            reader.start();
            assertTrue(firstSeen.await(15, TimeUnit.SECONDS));
            Thread.sleep(100);
            reader.interrupt();
            reader.join(15_000);
            assertFalse(reader.isAlive());
            assertTrue(thrown.get() instanceof InterruptedRequestException, String.valueOf(thrown.get()));
            assertTrue(flag.get());
        }
    }

    @Test
    void theParserHandlesVeryLongLines() {
        int size = 8 * 1024 * 1024 + 17;
        byte[] payload = new byte[size];
        java.util.Arrays.fill(payload, (byte) 'x');
        SseParser parser = new SseParser();
        List<ServerSentEvent> events = new ArrayList<>();
        parser.feed("data: ".getBytes(StandardCharsets.UTF_8), events::add);
        for (int offset = 0; offset < size; offset += 65536) {
            parser.feed(payload, offset, Math.min(65536, size - offset), events::add);
        }
        parser.feed("\n\n".getBytes(StandardCharsets.UTF_8), events::add);
        assertEquals(1, events.size());
        assertEquals(size, events.get(0).data().length());
        assertEquals("message", events.get(0).event());
    }
}
