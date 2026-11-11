package kz.tungo.sdk.internal;

import java.util.function.Predicate;
import java.util.function.Supplier;
import kz.tungo.sdk.WaitOptions;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.WaitTimeoutException;

/**
 * The polling loop of the waiting helpers. Internal: not part of the public API.
 */
public final class Polling {
    private Polling() {
    }

    /**
     * Polls until the value reaches a final state or the deadline passes. The pause between
     * polls starts at the initial interval and grows by half up to the maximum.
     *
     * @param options deadline and intervals
     * @param fetch requests the current value
     * @param finished tells whether a value is final; may throw to abort the wait
     * @param what a short description of the awaited resource for the error message
     * @param <T> type of the value
     * @return the final value
     * @throws WaitTimeoutException when the deadline passes first
     * @throws InterruptedRequestException when the calling thread is interrupted
     */
    public static <T> T poll(WaitOptions options, Supplier<T> fetch, Predicate<T> finished, String what) {
        long deadline = System.nanoTime() + options.timeout().toNanos();
        long interval = options.initialInterval().toMillis();
        long max = Math.max(options.maxInterval().toMillis(), 1);
        while (true) {
            T value = fetch.get();
            if (finished.test(value)) {
                return value;
            }
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                throw new WaitTimeoutException("Timed out after " + options.timeout().toMillis()
                    + " ms waiting for " + what + " to finish");
            }
            try {
                Thread.sleep(Math.max(1, Math.min(Math.min(interval, max), remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedRequestException("Interrupted while waiting for " + what + " to finish", e);
            }
            interval = Math.min(interval + interval / 2, max);
        }
    }
}
