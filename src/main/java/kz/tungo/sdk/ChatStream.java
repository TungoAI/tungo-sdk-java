package kz.tungo.sdk;

import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.models.ChatEvent;

/**
 * A stream of chat events ({@link ChatEvent}) delivered as they arrive.
 *
 * <p>The stream holds an open connection: use it in a try-with-resources block. Closing it, or
 * reaching its end, closes the connection. It can be iterated once. An {@code error} event is
 * yielded as an ordinary typed event by the iterator; {@link #accumulate()} raises it.
 */
public final class ChatStream implements Iterable<ChatEvent>, AutoCloseable {
    private final EventStream<ChatEvent> events;

    /**
     * Wraps an event stream. Called by the generated resource methods.
     *
     * @param events the underlying stream
     */
    public ChatStream(EventStream<ChatEvent> events) {
        this.events = events;
    }

    /**
     * Returns the dialog id from the {@code X-Dialog-Id} response header.
     *
     * @return the dialog id, if the header is present
     */
    public Optional<String> dialogId() {
        return events.response().header("X-Dialog-Id").filter(s -> !s.isEmpty());
    }

    /**
     * Returns the turn number from the {@code X-Turn-No} response header.
     *
     * @return the turn number, if the header is present and numeric
     */
    public Optional<Long> turnNo() {
        Optional<String> raw = events.response().header("X-Turn-No");
        if (raw.isPresent()) {
            try {
                return Optional.of(Long.parseLong(raw.get().trim()));
            } catch (NumberFormatException ignored) {
                // not a number: treat as absent
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the HTTP details of the stream response: status, headers, request id, rate limit.
     *
     * @return the response (without a body)
     */
    public ApiResponse<Void> response() {
        return events.response();
    }

    /**
     * Returns the id of the last event received so far, for reconnecting with
     * {@code Last-Event-ID}.
     *
     * @return the id, if the stream has sent one
     */
    public Optional<String> lastEventId() {
        return events.lastEventId();
    }

    /**
     * Returns the iterator of the stream. Every call returns the same iterator.
     *
     * @return the single iterator of this stream
     */
    @Override
    public Iterator<ChatEvent> iterator() {
        return events.iterator();
    }

    /**
     * Returns the events as a sequential {@link Stream}. Closing that stream closes this one.
     *
     * @return a lazy stream over the remaining events
     */
    public Stream<ChatEvent> stream() {
        return events.stream();
    }

    /**
     * Reads the remaining events to the end of the turn and folds them into a result, then closes
     * the stream.
     *
     * @return the final answer, usage, cost and the events of the turn
     * @throws ApiException when the turn reported an {@code error} event; the subclass is chosen
     *     by the error type exactly as for HTTP errors
     * @throws DecodeException when the stream ended before completion (no {@code done} event, for
     *     example because the connection was cut or the stream was closed early): a partial
     *     answer is never returned as a finished turn
     */
    public ChatStreamResult accumulate() {
        try {
            ChatStreamAccumulator acc = new ChatStreamAccumulator();
            for (ChatEvent event : events) {
                acc.add(event);
            }
            Optional<ApiException> failure = acc.errorAsException(response().statusCode(), response().headers());
            if (failure.isPresent()) {
                throw failure.get();
            }
            if (!acc.isComplete()) {
                throw new DecodeException("The chat stream ended before completion: no \"done\" event arrived after "
                    + acc.eventCount() + " event(s)", null, response().statusCode(),
                    response().requestId().orElse(null), null);
            }
            return acc.result(dialogId().orElse(null), turnNo().orElse(null));
        } finally {
            close();
        }
    }

    /** Closes the connection. Safe to call more than once and from another thread. */
    @Override
    public void close() {
        events.close();
    }
}
