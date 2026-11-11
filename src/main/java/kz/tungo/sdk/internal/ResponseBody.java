package kz.tungo.sdk.internal;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The body of a response, read incrementally by one thread. Unlike the input stream of the JDK,
 * a read here honours a deadline and a thread interrupt on every supported Java version, and
 * closing it cancels the exchange, which closes the connection of an unfinished response.
 * Internal: not part of the public API.
 */
final class ResponseBody implements HttpResponse.BodySubscriber<ResponseBody>, AutoCloseable {
    private static final Object END = new Object();
    private static final Object CLOSED = new Object();

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Flow.Subscription subscription;
    private Iterator<ByteBuffer> buffers = Collections.emptyIterator();
    private ByteBuffer current;
    private boolean ended;

    @Override
    public CompletionStage<ResponseBody> getBody() {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        if (closed.get()) {
            s.cancel();
        } else {
            s.request(1);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        queue.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        queue.add(throwable);
    }

    @Override
    public void onComplete() {
        queue.add(END);
    }

    /**
     * Reads some bytes, waiting until the deadline at most.
     *
     * @param target the buffer to fill
     * @param deadlineNanos the {@link System#nanoTime()} value after which the read gives up
     * @return the number of bytes read, or -1 at the end of the body
     * @throws IOException when the connection fails or the body was closed
     * @throws InterruptedException when the thread is interrupted while waiting
     * @throws TimeoutException when no bytes arrive before the deadline
     */
    int read(byte[] target, long deadlineNanos) throws IOException, InterruptedException, TimeoutException {
        while (current == null || !current.hasRemaining()) {
            if (buffers.hasNext()) {
                current = buffers.next();
                continue;
            }
            if (ended) {
                return -1;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Object item = queue.poll(Math.max(deadlineNanos - System.nanoTime(), 0L), TimeUnit.NANOSECONDS);
            if (item == null) {
                throw new TimeoutException();
            }
            if (item == END) {
                ended = true;
                return -1;
            }
            if (item == CLOSED) {
                ended = true;
                throw new IOException("the response body was closed");
            }
            if (item instanceof Throwable) {
                ended = true;
                Throwable t = (Throwable) item;
                throw t instanceof IOException ? (IOException) t : new IOException(t.toString(), t);
            }
            buffers = asBuffers(item).iterator();
            Flow.Subscription s = subscription;
            if (s != null) {
                s.request(1);
            }
        }
        int n = Math.min(target.length, current.remaining());
        current.get(target, 0, n);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<ByteBuffer> asBuffers(Object item) {
        return (List<ByteBuffer>) item;
    }

    /** Stops reading and cancels the exchange; an unfinished response loses its connection. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
            queue.add(CLOSED);
        }
    }

    boolean isClosed() {
        return closed.get();
    }
}
