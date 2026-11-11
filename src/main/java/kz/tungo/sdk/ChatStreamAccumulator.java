package kz.tungo.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatEventDone;
import kz.tungo.sdk.models.ChatEventError;
import kz.tungo.sdk.models.ChatEventProgress;
import kz.tungo.sdk.models.ChatEventText;
import kz.tungo.sdk.models.ChatUsage;

/**
 * Folds the events of one chat turn into a {@link ChatStreamResult}.
 *
 * <p>Text deltas are concatenated; a {@code progress} event removes the deltas of the step it
 * names (that text was interim narration); the {@code answer} of the {@code done} event, when
 * present, replaces the accumulated text. The first {@code error} event is remembered and can be
 * raised as a typed exception. A turn is complete only when its {@code done} event arrived: a
 * stream that ends earlier was cut off, and {@link #result} refuses to present its partial text
 * as an answer. Not thread-safe.
 */
public final class ChatStreamAccumulator {
    private final List<ChatEvent> events = new ArrayList<>();
    private final List<Double> steps = new ArrayList<>();
    private final List<String> deltas = new ArrayList<>();
    private ChatEventDone done;
    private ChatEventError error;

    /** Creates an empty accumulator. */
    public ChatStreamAccumulator() {
    }

    /**
     * Adds the next event of the turn.
     *
     * @param event the event
     * @return this accumulator
     */
    public ChatStreamAccumulator add(ChatEvent event) {
        events.add(event);
        if (event instanceof ChatEventText) {
            ChatEventText text = (ChatEventText) event;
            steps.add(text.step().orElse(null));
            deltas.add(text.delta() == null ? "" : text.delta());
        } else if (event instanceof ChatEventProgress) {
            Double step = ((ChatEventProgress) event).step();
            if (step != null) {
                for (int i = steps.size() - 1; i >= 0; i--) {
                    if (step.equals(steps.get(i))) {
                        steps.remove(i);
                        deltas.remove(i);
                    }
                }
            }
        } else if (event instanceof ChatEventError) {
            if (error == null) {
                error = (ChatEventError) event;
            }
        } else if (event instanceof ChatEventDone) {
            done = (ChatEventDone) event;
        }
        return this;
    }

    /**
     * Returns the number of events added so far.
     *
     * @return the number of events
     */
    public int eventCount() {
        return events.size();
    }

    /**
     * Tells whether the {@code done} event of the turn arrived.
     *
     * @return false while the turn is running, and for a stream that ended before completion
     */
    public boolean isComplete() {
        return done != null;
    }

    /**
     * Returns the text accumulated so far, for showing progress. It is the final answer only
     * when {@link #isComplete()} is true.
     *
     * @return the text of the deltas received so far, or the answer of the {@code done} event
     */
    public String textSoFar() {
        if (done != null && done.answer().isPresent()) {
            return done.answer().get();
        }
        return String.join("", deltas);
    }

    /**
     * Returns the first {@code error} event of the turn.
     *
     * @return the error event, if the turn reported one
     */
    public Optional<ChatEventError> error() {
        return Optional.ofNullable(error);
    }

    /**
     * Converts the first {@code error} event into the typed exception of the SDK.
     *
     * @param statusCode HTTP status of the stream response
     * @param headers headers of the stream response, may be null
     * @return the exception, if the turn reported an error
     */
    public Optional<ApiException> errorAsException(int statusCode, HttpHeaders headers) {
        if (error == null) {
            return Optional.empty();
        }
        JsonNode detail = error.error() == null ? null : Json.toTree(error.error());
        if (detail == null || !detail.isObject()) {
            detail = Json.MAPPER.createObjectNode();
        }
        ObjectNode node = (ObjectNode) detail;
        return Optional.of(ApiException.fromStreamError(statusCode, headers, node, error.requestId().orElse(null)));
    }

    /**
     * Builds the result of the finished turn.
     *
     * @param dialogId the dialog id of the response headers, may be null
     * @param turnNo the turn number of the response headers, may be null
     * @return the folded result
     * @throws DecodeException when no {@code done} event was added: the stream ended before
     *     completion and its partial text is not an answer (see {@link #textSoFar()})
     */
    public ChatStreamResult result(String dialogId, Long turnNo) {
        if (done == null) {
            throw new DecodeException("The chat stream ended before completion: no \"done\" event arrived after "
                + events.size() + " event(s)", null);
        }
        return new ChatStreamResult(textSoFar(), done.stopReason().orElse(null), done.costTiyn().orElse(null),
            done.usage().orElse(null), done.choiceId().orElse(null), dialogId, turnNo, events);
    }
}
