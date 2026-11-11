package kz.tungo.sdk;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import kz.tungo.sdk.internal.Json;

/**
 * The payload of an incoming hook ({@code POST /hooks/{token}}): the exact bytes to send, their
 * content type, and optionally the secret that signs them and an event id. Instances are
 * immutable.
 */
public final class HookRequest {
    private final byte[] body;
    private final String contentType;
    private final String secret;
    private final String eventId;

    private HookRequest(Builder b) {
        this.body = b.body;
        this.contentType = b.contentType;
        this.secret = b.secret;
        this.eventId = b.eventId;
    }

    /**
     * Creates a request with a JSON body.
     *
     * @param payload any value Jackson can serialize (a map, a list, a model, a JSON tree)
     * @return a new builder
     */
    public static Builder json(Object payload) {
        return new Builder(Json.encode(payload), "application/json");
    }

    /**
     * Creates a request with a text body sent as UTF-8.
     *
     * @param text the body text
     * @param contentType the media type, for example {@code text/plain; charset=utf-8}
     * @return a new builder
     */
    public static Builder text(String text, String contentType) {
        return new Builder(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), contentType);
    }

    /**
     * Creates a request with a body of arbitrary bytes.
     *
     * @param body the exact bytes to send
     * @param contentType the media type, may be null
     * @return a new builder
     */
    public static Builder bytes(byte[] body, String contentType) {
        return new Builder(Objects.requireNonNull(body, "body").clone(), contentType);
    }

    /**
     * Returns the exact bytes that are sent.
     *
     * @return a copy of the body
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the media type of the body.
     *
     * @return the content type, if set
     */
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /**
     * Returns the event id sent in the {@code X-Event-Id} header.
     *
     * @return the event id, if set
     */
    public Optional<String> eventId() {
        return Optional.ofNullable(eventId);
    }

    /**
     * Returns the value of the {@code X-Signature} header.
     *
     * @return {@code sha256=<hex>} computed over the body, if a secret was given
     */
    public Optional<String> signature() {
        return secret == null ? Optional.empty() : Optional.of(TungoWebhooks.signHook(body, secret));
    }

    @Override
    public String toString() {
        return "HookRequest{bytes=" + body.length + ", contentType=" + contentType + ", signed=" + (secret != null)
            + ", eventId=" + eventId + "}";
    }

    /** Builder of {@link HookRequest}. */
    public static final class Builder {
        private final byte[] body;
        private final String contentType;
        private String secret;
        private String eventId;

        private Builder(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        /**
         * Signs the body with the hook secret: the request carries
         * {@code X-Signature: sha256=<hex>}.
         *
         * @param secret the hook secret; null sends the request unsigned
         * @return this builder
         * @throws kz.tungo.sdk.errors.ValidationException when the secret is empty
         */
        public Builder secret(String secret) {
            if (secret != null && secret.isEmpty()) {
                throw new kz.tungo.sdk.errors.ValidationException("The hook secret must not be empty");
            }
            this.secret = secret;
            return this;
        }

        /**
         * Sets the event id sent in the {@code X-Event-Id} header (deduplication on the server).
         *
         * @param eventId the event id; null sends no header
         * @return this builder
         */
        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return an immutable hook request
         */
        public HookRequest build() {
            return new HookRequest(this);
        }
    }
}
