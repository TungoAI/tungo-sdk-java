package kz.tungo.sdk.internal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.tungo.sdk.ApiResponse;
import kz.tungo.sdk.ServerSentEvent;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.TungoTimeoutException;

/**
 * An open server-sent event response: reads the body incrementally and yields raw events.
 * Internal: not part of the public API.
 */
public final class SseConnection implements AutoCloseable {
    private final ApiResponse<Void> response;
    private final ResponseBody body;
    private final long idleNanos;
    private final SseParser parser = new SseParser();
    private final ArrayDeque<ServerSentEvent> pending = new ArrayDeque<>();
    private final byte[] buffer = new byte[8192];
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String lastEventId;
    private boolean finished;

    SseConnection(ApiResponse<Void> response, ResponseBody body, long idleNanos) {
        this.response = response;
        this.body = body;
        this.idleNanos = idleNanos;
    }

    /**
     * Returns the HTTP details of the response.
     *
     * @return the response (without a body)
     */
    public ApiResponse<Void> response() {
        return response;
    }

    /**
     * Returns the id of the last event received so far.
     *
     * @return the id, or null when the stream has not sent one
     */
    public String lastEventId() {
        return lastEventId;
    }

    /**
     * Blocks until the next event arrives.
     *
     * @return the next event, or null at the end of the stream (also after {@link #close()})
     * @throws TungoTimeoutException when no bytes arrived within the idle timeout
     * @throws ConnectionException when the connection failed
     * @throws InterruptedRequestException when the calling thread was interrupted
     */
    public ServerSentEvent next() {
        while (pending.isEmpty() && !finished) {
            if (closed.get()) {
                finished = true;
                break;
            }
            int n;
            try {
                n = body.read(buffer, System.nanoTime() + idleNanos);
            } catch (InterruptedException e) {
                finished = true;
                close();
                Thread.currentThread().interrupt();
                throw new InterruptedRequestException("Interrupted while reading the event stream", e);
            } catch (TimeoutException e) {
                finished = true;
                close();
                throw new TungoTimeoutException("No data arrived on the event stream for " + idleNanos / 1_000_000L + " ms", e);
            } catch (IOException e) {
                finished = true;
                boolean wasClosed = closed.get();
                close();
                if (wasClosed) {
                    break;
                }
                throw new ConnectionException("The event stream was interrupted: " + HttpEngine.describe(e), e);
            }
            if (n < 0) {
                finished = true;
                close();
                break;
            }
            parser.feed(buffer, 0, n, pending::add);
        }
        ServerSentEvent event = pending.poll();
        if (event != null && event.id().isPresent()) {
            lastEventId = event.id().get();
        }
        return event;
    }

    /** Closes the stream and its connection. Safe to call from another thread and more than once. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            body.close();
        }
    }
}
