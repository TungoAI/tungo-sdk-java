package kz.tungo.sdk;

import java.time.Duration;

/**
 * Options of the polling helpers ({@code waitForRun}, {@code waitForBatch},
 * {@code runScenarioAndWait}). Instances are immutable.
 */
public final class WaitOptions {
    private static final WaitOptions DEFAULTS = new WaitOptions(new Builder());

    private final Duration timeout;
    private final Duration initialInterval;
    private final Duration maxInterval;
    private final RequestOptions requestOptions;

    private WaitOptions(Builder b) {
        this.timeout = b.timeout;
        this.initialInterval = b.initialInterval;
        this.maxInterval = b.maxInterval;
        this.requestOptions = b.requestOptions;
    }

    /**
     * Returns the defaults: an overall deadline of 10 minutes, polling every second at first and
     * slowing down to every 5 seconds.
     *
     * @return the default options
     */
    public static WaitOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Starts building wait options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the overall deadline of the wait.
     *
     * @return the deadline
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the pause before the second poll.
     *
     * @return the first polling interval
     */
    public Duration initialInterval() {
        return initialInterval;
    }

    /**
     * Returns the longest pause between polls.
     *
     * @return the largest polling interval
     */
    public Duration maxInterval() {
        return maxInterval;
    }

    /**
     * Returns the options applied to every polling request.
     *
     * @return the request options
     */
    public RequestOptions requestOptions() {
        return requestOptions;
    }

    /** Builder of {@link WaitOptions}. */
    public static final class Builder {
        private Duration timeout = Duration.ofMinutes(10);
        private Duration initialInterval = Duration.ofSeconds(1);
        private Duration maxInterval = Duration.ofSeconds(5);
        private RequestOptions requestOptions = RequestOptions.none();

        private Builder() {
        }

        /**
         * Sets the overall deadline of the wait.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = TungoClient.Builder.positive(timeout, "timeout");
            return this;
        }

        /**
         * Sets the pause before the second poll; later pauses grow by half up to the maximum.
         *
         * @param initialInterval a positive duration
         * @return this builder
         */
        public Builder initialInterval(Duration initialInterval) {
            this.initialInterval = TungoClient.Builder.positive(initialInterval, "initialInterval");
            return this;
        }

        /**
         * Sets the longest pause between polls.
         *
         * @param maxInterval a positive duration
         * @return this builder
         */
        public Builder maxInterval(Duration maxInterval) {
            this.maxInterval = TungoClient.Builder.positive(maxInterval, "maxInterval");
            return this;
        }

        /**
         * Sets the options applied to every polling request.
         *
         * @param requestOptions the request options
         * @return this builder
         */
        public Builder requestOptions(RequestOptions requestOptions) {
            this.requestOptions = requestOptions == null ? RequestOptions.none() : requestOptions;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return immutable wait options
         */
        public WaitOptions build() {
            return new WaitOptions(this);
        }
    }
}
