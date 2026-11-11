package kz.tungo.sdk;

import java.net.http.HttpHeaders;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Rate-limit information of one response ({@code x-ratelimit-*} headers).
 */
public final class RateLimit {
    private final long limit;
    private final long remaining;
    private final long reset;

    private RateLimit(long limit, long remaining, long reset) {
        this.limit = limit;
        this.remaining = remaining;
        this.reset = reset;
    }

    /**
     * Reads the rate-limit headers of a response.
     *
     * @param headers response headers
     * @return the rate-limit information, empty unless all three headers hold whole numbers
     */
    public static Optional<RateLimit> from(HttpHeaders headers) {
        OptionalLong limit = number(headers, "x-ratelimit-limit");
        OptionalLong remaining = number(headers, "x-ratelimit-remaining");
        OptionalLong reset = number(headers, "x-ratelimit-reset");
        if (limit.isPresent() && remaining.isPresent() && reset.isPresent()) {
            return Optional.of(new RateLimit(limit.getAsLong(), remaining.getAsLong(), reset.getAsLong()));
        }
        return Optional.empty();
    }

    private static OptionalLong number(HttpHeaders headers, String name) {
        Optional<String> value = headers.firstValue(name);
        if (value.isPresent()) {
            try {
                return OptionalLong.of(Long.parseLong(value.get().trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the number of requests allowed in the current window.
     *
     * @return {@code x-ratelimit-limit}
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the number of requests left in the current window.
     *
     * @return {@code x-ratelimit-remaining}
     */
    public long remaining() {
        return remaining;
    }

    /**
     * Returns when the window resets, exactly as the API reports it.
     *
     * @return {@code x-ratelimit-reset}
     */
    public long reset() {
        return reset;
    }

    @Override
    public String toString() {
        return "RateLimit{limit=" + limit + ", remaining=" + remaining + ", reset=" + reset + "}";
    }
}
