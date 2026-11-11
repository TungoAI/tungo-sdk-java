package kz.tungo.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatUsage;

/**
 * The outcome of one streamed chat turn, folded from its events.
 */
public final class ChatStreamResult {
    private final String text;
    private final String stopReason;
    private final Long costTiyn;
    private final ChatUsage usage;
    private final String choiceId;
    private final String dialogId;
    private final Long turnNo;
    private final List<ChatEvent> events;

    ChatStreamResult(String text, String stopReason, Long costTiyn, ChatUsage usage, String choiceId,
        String dialogId, Long turnNo, List<ChatEvent> events) {
        this.text = text;
        this.stopReason = stopReason;
        this.costTiyn = costTiyn;
        this.usage = usage;
        this.choiceId = choiceId;
        this.dialogId = dialogId;
        this.turnNo = turnNo;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * Returns the final answer: the {@code answer} of the {@code done} event when present,
     * otherwise the concatenated text deltas without the deltas of steps that a later
     * {@code progress} event replaced.
     *
     * @return the answer text, possibly empty
     */
    public String text() {
        return text;
    }

    /**
     * Returns why the turn stopped ({@code done.stop_reason}).
     *
     * @return the stop reason; empty when the {@code done} event did not carry one
     */
    public Optional<String> stopReason() {
        return Optional.ofNullable(stopReason);
    }

    /**
     * Returns the cost of the turn in tiyn ({@code done.cost_tiyn}).
     *
     * @return the cost, if the API reported it
     */
    public Optional<Long> costTiyn() {
        return Optional.ofNullable(costTiyn);
    }

    /**
     * Returns the token usage of the turn ({@code done.usage}).
     *
     * @return the usage, if the API reported it
     */
    public Optional<ChatUsage> usage() {
        return Optional.ofNullable(usage);
    }

    /**
     * Returns the id of the pending choice the turn stopped on ({@code done.choice_id}).
     *
     * @return the choice id, if the turn is waiting for an answer
     */
    public Optional<String> choiceId() {
        return Optional.ofNullable(choiceId);
    }

    /**
     * Returns the dialog id from the {@code X-Dialog-Id} response header.
     *
     * @return the dialog id, if the header was present
     */
    public Optional<String> dialogId() {
        return Optional.ofNullable(dialogId);
    }

    /**
     * Returns the turn number from the {@code X-Turn-No} response header.
     *
     * @return the turn number, if the header was present
     */
    public Optional<Long> turnNo() {
        return Optional.ofNullable(turnNo);
    }

    /**
     * Returns every event of the turn in order, without keep-alive pings.
     *
     * @return an unmodifiable list
     */
    public List<ChatEvent> events() {
        return events;
    }

    @Override
    public String toString() {
        return "ChatStreamResult{text=" + text + ", stopReason=" + stopReason + ", dialogId=" + dialogId
            + ", turnNo=" + turnNo + ", events=" + events.size() + "}";
    }
}
