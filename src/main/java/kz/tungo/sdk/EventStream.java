package kz.tungo.sdk;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import kz.tungo.sdk.internal.SseConnection;

/**
 * A stream of typed server-sent events, delivered as they arrive.
 *
 * <p>The stream holds an open connection: use it in a try-with-resources block. Closing it, or
 * reaching its end, closes the connection. It can be iterated once. Reading blocks the calling
 * thread; interrupting the thread aborts the read.
 *
 * @param <T> type of the events
 */
public final class EventStream<T> implements Iterable<T>, AutoCloseable {
    private final SseConnection connection;
    private final Function<ServerSentEvent, T> decoder;
    private final Iterator<T> iterator = new EventIterator();

    /**
     * Creates a stream. Called by the generated resource methods.
     *
     * @param connection the open connection
     * @param decoder turns a raw event into a typed one; returns null to skip the event
     */
    public EventStream(SseConnection connection, Function<ServerSentEvent, T> decoder) {
        this.connection = connection;
        this.decoder = decoder;
    }

    /**
     * Returns the HTTP details of the stream response: status, headers, request id, rate limit.
     *
     * @return the response (without a body)
     */
    public ApiResponse<Void> response() {
        return connection.response();
    }

    /**
     * Returns the id of the last event received so far, for reconnecting with
     * {@code Last-Event-ID}.
     *
     * @return the id, if the stream has sent one
     */
    public Optional<String> lastEventId() {
        return Optional.ofNullable(connection.lastEventId());
    }

    /**
     * Returns the iterator of the stream. Every call returns the same iterator.
     *
     * @return the single iterator of this stream
     */
    @Override
    public Iterator<T> iterator() {
        return iterator;
    }

    /**
     * Returns the events as a sequential {@link Stream}. Closing that stream closes this one.
     *
     * @return a lazy stream over the remaining events
     */
    public Stream<T> stream() {
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
            .onClose(this::close);
    }

    /** Closes the connection. Safe to call more than once and from another thread. */
    @Override
    public void close() {
        connection.close();
    }

    private final class EventIterator implements Iterator<T> {
        private T next;
        private boolean done;

        @Override
        public boolean hasNext() {
            while (next == null && !done) {
                ServerSentEvent raw = connection.next();
                if (raw == null) {
                    done = true;
                    connection.close();
                } else {
                    try {
                        next = decoder.apply(raw);
                    } catch (RuntimeException | Error e) {
                        done = true;
                        connection.close();
                        throw e;
                    }
                }
            }
            return next != null;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T value = next;
            next = null;
            return value;
        }
    }
}
