package kz.tungo.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import kz.tungo.sdk.ServerSentEvent;
import kz.tungo.sdk.errors.ApiException;

/**
 * Decoders that turn raw server-sent events into typed values. Internal: not part of the public
 * API.
 */
public final class StreamDecoders {
    private StreamDecoders() {
    }

    /**
     * Decodes events whose data is a tagged union (the tag inside the JSON selects the variant,
     * so events of any name are decoded). Keep-alive pings and events without data are skipped.
     *
     * @param type the union class
     * @param <T> the union type
     * @return the decoder
     */
    public static <T> Function<ServerSentEvent, T> union(Class<T> type) {
        return event -> {
            if (event.event().equals("ping") || event.data().isEmpty()) {
                return null;
            }
            return Json.decodeText(event.data(), type, "the \"" + event.event() + "\" stream event");
        };
    }

    /**
     * Decodes events of the given names into one type. Keep-alive pings, events without data and
     * events of other names are skipped; an {@code error} event ends the stream with an
     * {@link ApiException}.
     *
     * @param type the event class
     * @param connection the connection, for the status and headers of an error
     * @param names the event names that carry the type
     * @param <T> the event type
     * @return the decoder
     */
    public static <T> Function<ServerSentEvent, T> named(Class<T> type, SseConnection connection, String... names) {
        Set<String> accepted = new HashSet<>(Arrays.asList(names));
        accepted.add("message");
        return event -> {
            if (event.event().equals("ping") || event.data().isEmpty()) {
                return null;
            }
            if (event.event().equals("error")) {
                throw streamError(connection, event.data());
            }
            if (!accepted.contains(event.event())) {
                return null;
            }
            return Json.decodeText(event.data(), type, "the \"" + event.event() + "\" stream event");
        };
    }

    private static ApiException streamError(SseConnection connection, String data) {
        ObjectNode error = Json.MAPPER.createObjectNode();
        JsonNode parsed = Json.tryParse(data);
        String requestId = null;
        if (parsed != null && parsed.isObject()) {
            JsonNode nested = parsed.get("error");
            if (nested != null && nested.isObject()) {
                // the envelope of the REST API: {type:"error", error:{type, code, message}, request_id}
                error.setAll((ObjectNode) nested);
                JsonNode rid = parsed.get("request_id");
                requestId = rid != null && rid.isTextual() ? rid.asText() : null;
            } else {
                // the short form of the feed: {error: "<code>", message: "<text>"}
                if (nested != null && nested.isTextual()) {
                    error.put("code", nested.asText());
                }
                JsonNode message = parsed.get("message");
                if (message != null && message.isTextual()) {
                    error.put("message", message.asText());
                }
            }
        } else if (!data.isEmpty()) {
            error.put("message", data);
        }
        if (!error.has("type")) {
            error.put("type", "api_error");
        }
        return ApiException.fromStreamError(connection.response().statusCode(), connection.response().headers(), error, requestId);
    }
}
