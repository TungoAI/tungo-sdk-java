package kz.tungo.sdk;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Options that apply to one request and override the client configuration.
 *
 * <p>Instances are immutable. Cancellation is not an option: interrupt the calling thread (sync
 * client) or cancel the returned future (async client).
 */
public final class RequestOptions {
    private static final RequestOptions NONE = new RequestOptions(new Builder());

    private final Duration timeout;
    private final Integer maxRetries;
    private final String idempotencyKey;
    private final Duration streamIdleTimeout;
    private final Map<String, String> headers;

    private RequestOptions(Builder b) {
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.idempotencyKey = b.idempotencyKey;
        this.streamIdleTimeout = b.streamIdleTimeout;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    }

    /**
     * Returns options that override nothing.
     *
     * @return the empty options
     */
    public static RequestOptions none() {
        return NONE;
    }

    /**
     * Starts building request options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the timeout of the whole request, including reading the body.
     *
     * @return the timeout, if overridden
     */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns the maximum number of retries.
     *
     * @return the number of retries, if overridden
     */
    public Optional<Integer> maxRetries() {
        return Optional.ofNullable(maxRetries);
    }

    /**
     * Returns the caller-supplied idempotency key.
     *
     * @return the key, if set
     */
    public Optional<String> idempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    /**
     * Returns the longest pause without any bytes a streaming call tolerates.
     *
     * @return the idle timeout, if overridden
     */
    public Optional<Duration> streamIdleTimeout() {
        return Optional.ofNullable(streamIdleTimeout);
    }

    /**
     * Returns the extra request headers.
     *
     * @return an unmodifiable map of header names to values
     */
    public Map<String, String> headers() {
        return headers;
    }

    /** Builder of {@link RequestOptions}. */
    public static final class Builder {
        private Duration timeout;
        private Integer maxRetries;
        private String idempotencyKey;
        private Duration streamIdleTimeout;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the timeout of the whole request, including reading the body.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = TungoClient.Builder.positive(timeout, "timeout");
            return this;
        }

        /**
         * Sets the maximum number of retries; 0 disables retries.
         *
         * @param maxRetries zero or more
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the {@code Idempotency-Key} of a request to an operation that declares the header,
         * instead of the generated one. The same key is sent with every attempt. Any other
         * operation sends no key, not even this one, and a POST or PATCH among them is never
         * retried: the API would ignore the key there.
         *
         * @param idempotencyKey a non-empty key
         * @return this builder
         */
        public Builder idempotencyKey(String idempotencyKey) {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.isEmpty()) {
                throw new IllegalArgumentException("idempotencyKey must not be empty");
            }
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * Sets the longest pause without any bytes a streaming call tolerates.
         *
         * @param streamIdleTimeout a positive duration
         * @return this builder
         */
        public Builder streamIdleTimeout(Duration streamIdleTimeout) {
            this.streamIdleTimeout = TungoClient.Builder.positive(streamIdleTimeout, "streamIdleTimeout");
            return this;
        }

        /**
         * Adds a request header; it replaces a default header of the same name.
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Builds the options.
         *
         * @return immutable request options
         */
        public RequestOptions build() {
            return new RequestOptions(this);
        }
    }
}
