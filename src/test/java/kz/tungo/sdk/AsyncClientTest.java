package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kz.tungo.sdk.errors.NotFoundException;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.Company;
import kz.tungo.sdk.models.ProposalListItem;
import kz.tungo.sdk.models.ProposalPage;
import org.junit.jupiter.api.Test;

class AsyncClientTest {
    @Test
    void asyncMethodsCompleteWithTheSameTypedResults() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"id\":\"co_1\",\"permissions\":[],\"name\":\"n\"}", "x-request-id", "req_a");
            CompletableFuture<ApiResponse<Company>> future = client.async().companies().getWithResponse("co_1", RequestOptions.none());
            ApiResponse<Company> response = future.get(20, TimeUnit.SECONDS);
            assertEquals("co_1", response.body().id());
            assertEquals("req_a", response.requestId().orElseThrow());
            assertEquals(TestSupport.API_KEY, server.request(0).header("x-api-key"));

            server.enqueueJson(200, "{\"data\":[{\"id\":\"p1\"}],\"counts\":{},\"next_cursor\":\"c1\"}")
                .enqueueJson(200, "{\"data\":[{\"id\":\"p2\"}],\"counts\":{},\"next_cursor\":null}");
            Page<ProposalListItem, ProposalPage> page = client.async().proposals().list("co_1").get(20, TimeUnit.SECONDS);
            assertEquals("p1", page.data().get(0).id());
            assertEquals("p2", page.nextPage().data().get(0).id());
        }
    }

    @Test
    void failuresCompleteTheFutureExceptionally() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(404, TestSupport.envelope("not_found_error", "not_found", "No"));
            ExecutionException e = assertThrows(ExecutionException.class,
                () -> client.async().companies().get("co_x").get(20, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof NotFoundException, String.valueOf(e.getCause()));

            ExecutionException local = assertThrows(ExecutionException.class,
                () -> client.async().companies().get("..").get(20, TimeUnit.SECONDS));
            assertTrue(local.getCause() instanceof kz.tungo.sdk.errors.ValidationException);
        }
    }

    @Test
    void cancellingTheFutureAbortsTheRequest() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            CountDownLatch arrived = new CountDownLatch(1);
            AtomicBoolean clientGone = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            server.always((request, exchange) -> {
                arrived.countDown();
                clientGone.set(exchange.clientClosed(3_000));
                finished.countDown();
            });
            CompletableFuture<Company> future = client.async().companies().get("co_1");
            assertTrue(arrived.await(10, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertThrows(CancellationException.class, future::join);
            assertTrue(finished.await(20, TimeUnit.SECONDS));
            if (Runtime.version().feature() >= 16) {
                // java.net.http can abort an exchange that waits for its response only since Java 16;
                // on older versions the caller is released at once and the connection ends with the timeout.
                assertTrue(clientGone.get(), "cancelling must close the connection");
            }
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void closingTheClientFailsTheQueuedCalls() throws Exception {
        try (MockServer server = new MockServer()) {
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.always((request, exchange) -> {
                arrived.countDown();
                release.await(20, TimeUnit.SECONDS);
                exchange.json(200, "{\"id\":\"co_1\",\"permissions\":[]}");
            });
            TungoClient client = TestSupport.builder(server).asyncMaxThreads(1).build();
            CompletableFuture<Company> running = client.async().companies().get("co_1");
            CompletableFuture<Company> queued = client.async().companies().get("co_2");
            CompletableFuture<String> dependent = queued.handle((c, e) -> e == null ? "ok" : "failed");
            try {
                assertTrue(arrived.await(10, TimeUnit.SECONDS));
                client.close();
                // The queued call never ran: its future fails at once instead of never completing.
                ExecutionException e = assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS));
                assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
                assertEquals("failed", dependent.get(5, TimeUnit.SECONDS));
                // The running call is interrupted and fails too.
                assertThrows(ExecutionException.class, () -> running.get(20, TimeUnit.SECONDS));
                assertEquals(1, server.requestCount());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void asyncStreamsAndHelpersWork() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueue((request, exchange) -> {
                exchange.sse("X-Dialog-Id", "d-1", "X-Turn-No", "2");
                exchange.write("event: text\ndata: {\"type\":\"text\",\"delta\":\"Привет\"}\n\n"
                    + "event: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\"}\n\n");
            });
            try (ChatStream stream = client.async().chat()
                .createStream("co_1", ChatMessageRequest.builder().message("m").build()).get(20, TimeUnit.SECONDS)) {
                ChatStreamResult result = stream.accumulate();
                assertEquals("Привет", result.text());
                assertEquals("d-1", result.dialogId().orElseThrow());
            }
            server.enqueueJson(200, "{\"answer\":\"42\",\"dialog_id\":\"d\",\"turn_no\":1,\"citations\":[],\"errors\":[],\"followups\":[],\"proposals\":[],\"questions\":[]}");
            assertEquals("42", client.async().ask("co_1", "q").get(20, TimeUnit.SECONDS).answer());
        }
    }

    @Test
    void runsOnTheSuppliedExecutor() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "my-async-thread"));
        try (MockServer server = new MockServer()) {
            TungoClient client = TestSupport.builder(server).asyncExecutor(executor).build();
            server.enqueueJson(200, "{\"id\":\"co_1\",\"permissions\":[]}");
            client.async().companies().get("co_1").thenAccept(c -> threadName.compareAndSet(null, Thread.currentThread().getName()))
                .get(20, TimeUnit.SECONDS);
            assertEquals("my-async-thread", threadName.get());
            assertSame(client.async(), client.async());
            assertSame(client, client.async().sync());
            client.close();
            assertTrue(!executor.isShutdown(), "a supplied executor is not shut down by the client");
        } finally {
            executor.shutdownNow();
        }
    }
}
