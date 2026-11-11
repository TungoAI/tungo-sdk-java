package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.NotFoundException;
import kz.tungo.sdk.errors.WaitTimeoutException;
import kz.tungo.sdk.models.AutomationRunDetail;
import kz.tungo.sdk.models.BatchJob;
import org.junit.jupiter.api.Test;

class WaitersTest {
    private static final WaitOptions FAST = WaitOptions.builder().initialInterval(Duration.ofMillis(10))
        .maxInterval(Duration.ofMillis(20)).timeout(Duration.ofSeconds(20)).build();

    private static String run(String state) {
        return "{\"id\":\"run_1\",\"proposals\":[],\"steps\":[],\"tools\":[]"
            + (state == null ? "" : ",\"state\":\"" + state + "\"") + "}";
    }

    @Test
    void defaultsPollFromOneToFiveSeconds() {
        WaitOptions defaults = WaitOptions.defaults();
        assertEquals(Duration.ofSeconds(1), defaults.initialInterval());
        assertEquals(Duration.ofSeconds(5), defaults.maxInterval());
        assertTrue(defaults.timeout().compareTo(Duration.ofMinutes(1)) >= 0);
    }

    @Test
    void waitForRunPollsUntilAFinalState() {
        for (String last : new String[] {"done", "error", "skipped", "cancelled"}) {
            try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
                server.enqueueJson(200, run("queued")).enqueueJson(200, run("running")).enqueueJson(200, run(last));
                AutomationRunDetail result = client.waitForRun("co_1", "run_1", FAST);
                assertEquals(last, result.state().value());
                assertEquals(3, server.requestCount());
                assertEquals("/v1/companies/co_1/automations/runs/run_1", server.request(0).path());
            }
        }
    }

    @Test
    void anUnknownStateIsNotFinished() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, run("teleporting")));
            WaitOptions brief = WaitOptions.builder().initialInterval(Duration.ofMillis(10))
                .maxInterval(Duration.ofMillis(20)).timeout(Duration.ofMillis(300)).build();
            WaitTimeoutException e = assertThrows(WaitTimeoutException.class, () -> client.waitForRun("co_1", "run_1", brief));
            assertFalse(ApiException.class.isInstance(e));
            assertTrue(e.getMessage().contains("run_1"), e.getMessage());
            assertTrue(server.requestCount() >= 2);
        }
    }

    @Test
    void aMissingStateIsADecodeError() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, run(null)));
            assertThrows(DecodeException.class, () -> client.waitForRun("co_1", "run_1", FAST));
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void apiErrorsStopTheWait() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, run("running")).enqueueJson(404, TestSupport.envelope("not_found_error", "not_found", "No"));
            assertThrows(NotFoundException.class, () -> client.waitForRun("co_1", "run_1", FAST));
        }
    }

    @Test
    void runScenarioAndWaitStartsThenPolls() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(202, "{\"id\":\"run_7\",\"state\":\"queued\",\"proposals\":[],\"steps\":[],\"tools\":[]}").enqueueJson(200, run("running")).enqueueJson(200, run("done"));
            AutomationRunDetail result = client.runScenarioAndWait("co_1", "sc_1", null, FAST);
            assertEquals("done", result.state().value());
            assertEquals("POST", server.request(0).method);
            assertEquals("/v1/companies/co_1/automations/scenarios/sc_1/run", server.request(0).path());
            assertTrue(server.request(0).header("idempotency-key") != null);
            assertEquals("/v1/companies/co_1/automations/runs/run_7", server.request(1).path());
        }
    }

    @Test
    void waitForBatchPollsWhileRunning() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.enqueueJson(200, "{\"id\":\"b1\",\"running\":true}").enqueueJson(200, "{\"id\":\"b1\",\"running\":false}");
            BatchJob job = client.waitForBatch("co_1", "b1", FAST);
            assertFalse(job.running());
            assertEquals(2, server.requestCount());
            assertEquals("/v1/companies/co_1/batch/b1", server.request(0).path());

            server.enqueueJson(200, "{\"id\":\"b1\"}");
            assertThrows(DecodeException.class, () -> client.waitForBatch("co_1", "b1", FAST));
        }
    }

    @Test
    void anInterruptStopsTheWait() throws Exception {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            AtomicInteger polls = new AtomicInteger();
            server.always((request, exchange) -> {
                polls.incrementAndGet();
                exchange.json(200, run("running"));
            });
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> flag = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    client.waitForRun("co_1", "run_1");
                } catch (Throwable t) {
                    thrown.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (polls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100);
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive());
            assertTrue(thrown.get() instanceof InterruptedRequestException, String.valueOf(thrown.get()));
            assertTrue(flag.get());
        }
    }
}
