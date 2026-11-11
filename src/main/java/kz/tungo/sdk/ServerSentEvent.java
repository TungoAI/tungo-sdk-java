package kz.tungo.sdk;

import java.util.Objects;
import java.util.Optional;

/**
 * One raw server-sent event, before its data is decoded into a typed value.
 */
public final class ServerSentEvent {
    private final String event;
    private final String data;
    private final String id;
    private final Integer retry;

    /**
     * Creates an event.
     *
     * @param event the event name; {@code message} when the stream did not name it
     * @param data the data lines joined with a line feed
     * @param id the last event id seen so far, null when none
     * @param retry the reconnection time in milliseconds set by this event, null when none
     */
    public ServerSentEvent(String event, String data, String id, Integer retry) {
        this.event = Objects.requireNonNull(event, "event");
        this.data = Objects.requireNonNull(data, "data");
        this.id = id;
        this.retry = retry;
    }

    /**
     * Returns the event name.
     *
     * @return the name, {@code message} by default
     */
    public String event() {
        return event;
    }

    /**
     * Returns the event data.
     *
     * @return the data lines joined with a line feed, possibly empty
     */
    public String data() {
        return data;
    }

    /**
     * Returns the last event id seen so far.
     *
     * @return the id, if the stream sent one
     */
    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    /**
     * Returns the reconnection time set by this event.
     *
     * @return the time in milliseconds, if the event set one
     */
    public Optional<Integer> retry() {
        return Optional.ofNullable(retry);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerSentEvent)) {
            return false;
        }
        ServerSentEvent other = (ServerSentEvent) o;
        return event.equals(other.event) && data.equals(other.data)
            && Objects.equals(id, other.id) && Objects.equals(retry, other.retry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(event, data, id, retry);
    }

    @Override
    public String toString() {
        return "ServerSentEvent{event=" + event + ", data=" + data + ", id=" + id + ", retry=" + retry + "}";
    }
}
