package kz.tungo.sdk.internal;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Retry rules of the SDK: which requests may be repeated, how long to wait, how to read
 * {@code Retry-After}. Internal: not part of the public API.
 */
public final class RetryPolicy {
    /** A server delay above this many seconds is not slept: the error is raised instead. */
    public static final double MAX_RETRY_AFTER_SECONDS = 60.0;
    /** The first backoff delay in seconds. */
    public static final double BACKOFF_BASE_SECONDS = 0.5;
    /** The largest backoff delay in seconds. */
    public static final double BACKOFF_MAX_SECONDS = 8.0;
    /** Lower bound of the random jitter factor. */
    public static final double JITTER_MIN = 0.75;
    /** Upper bound of the random jitter factor. */
    public static final double JITTER_MAX = 1.0;
    /** Headers without any entry. */
    public static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Collections.emptyMap(), (a, b) -> true);

    private static final Pattern NON_NEGATIVE_NUMBER = Pattern.compile("^[0-9]+(\\.[0-9]+)?$");
    private static final DateTimeFormatter ASCTIME =
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(java.time.ZoneOffset.UTC);
    /**
     * The obsolete RFC 850 date, {@code Sunday, 06-Nov-94 08:49:37 GMT}. Its year has two digits:
     * RFC 9110 section 5.6.7 reads one that would be more than 50 years in the future as the most
     * recent past year with those digits, so the century window starts 49 years back.
     */
    private static final DateTimeFormatter RFC850 = new DateTimeFormatterBuilder()
        .appendPattern("EEEE, dd-MMM-")
        .appendValueReduced(ChronoField.YEAR, 2, 2, LocalDate.now(java.time.ZoneOffset.UTC).minusYears(49))
        .appendPattern(" HH:mm:ss 'GMT'")
        .toFormatter(Locale.US)
        .withZone(java.time.ZoneOffset.UTC);
    /** The same form with a four-digit year, which some senders write. */
    private static final DateTimeFormatter RFC850_FULL_YEAR =
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-uuuu HH:mm:ss 'GMT'", Locale.US).withZone(java.time.ZoneOffset.UTC);

    private RetryPolicy() {
    }

    /**
     * Tells whether a request may be repeated without risk of a duplicate side effect.
     *
     * @param method HTTP method
     * @param hasIdempotencyKey whether the request carries an {@code Idempotency-Key}
     * @return true for GET, HEAD, PUT, DELETE and for any request with a key
     */
    public static boolean isRetrySafe(String method, boolean hasIdempotencyKey) {
        if (hasIdempotencyKey) {
            return true;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return m.equals("GET") || m.equals("HEAD") || m.equals("PUT") || m.equals("DELETE");
    }

    /**
     * Tells whether a response status is worth retrying.
     *
     * @param status HTTP status
     * @return true for 408, 429, 500, 502, 503 and 504
     */
    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    /**
     * Computes the delay before a retry with a random jitter factor.
     *
     * @param attempt zero-based number of the retry
     * @return the delay in seconds
     */
    public static double backoffSeconds(int attempt) {
        return backoffSeconds(attempt, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Computes the delay before a retry: {@code min(0.5 * 2^attempt, 8) * jitter}.
     *
     * @param attempt zero-based number of the retry
     * @param random a number in [0, 1) that selects the jitter factor in [0.75, 1.0]
     * @return the delay in seconds
     */
    public static double backoffSeconds(int attempt, double random) {
        double base = attempt >= 5 ? BACKOFF_MAX_SECONDS : Math.min(BACKOFF_BASE_SECONDS * (1L << Math.max(attempt, 0)), BACKOFF_MAX_SECONDS);
        return base * (JITTER_MIN + (JITTER_MAX - JITTER_MIN) * random);
    }

    /**
     * Chooses the delay before a retry. A delay asked by the server is used when it is positive;
     * a zero delay or an HTTP-date that already passed says nothing useful, so the computed
     * backoff applies instead of an immediate retry.
     *
     * @param serverDelay the delay of {@code retry-after-ms} / {@code Retry-After} in seconds, may be null
     * @param attempt zero-based number of the retry
     * @return the delay in seconds
     */
    public static double delaySeconds(Double serverDelay, int attempt) {
        return delaySeconds(serverDelay, attempt, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Chooses the delay before a retry with a given jitter.
     *
     * @param serverDelay the delay asked by the server in seconds, may be null
     * @param attempt zero-based number of the retry
     * @param random a number in [0, 1) that selects the jitter factor
     * @return the server delay when it is positive, otherwise the computed backoff
     */
    public static double delaySeconds(Double serverDelay, int attempt, double random) {
        if (serverDelay != null && serverDelay > 0.0) {
            return serverDelay;
        }
        return backoffSeconds(attempt, random);
    }

    /**
     * Reads the delay asked by the server: {@code retry-after-ms} (milliseconds) wins over
     * {@code Retry-After} (seconds, fractions allowed, or an HTTP-date).
     *
     * @param headers response headers
     * @param now the current time, used for HTTP-dates
     * @return the delay in seconds, or null when there is no usable value
     */
    public static Double parseRetryAfter(HttpHeaders headers, Instant now) {
        String ms = headers.firstValue("retry-after-ms").map(String::trim).orElse(null);
        if (ms != null && NON_NEGATIVE_NUMBER.matcher(ms).matches()) {
            return Double.parseDouble(ms) / 1000.0;
        }
        String value = headers.firstValue("retry-after").map(String::trim).orElse(null);
        if (value == null) {
            return null;
        }
        if (NON_NEGATIVE_NUMBER.matcher(value).matches()) {
            return Double.parseDouble(value);
        }
        Instant at = parseHttpDate(value);
        if (at == null) {
            return null;
        }
        double seconds = (at.toEpochMilli() - now.toEpochMilli()) / 1000.0;
        return Math.max(0.0, seconds);
    }

    private static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the obsolete formats below
        }
        try {
            return ZonedDateTime.parse(value, RFC850).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a four-digit year below
        }
        try {
            return ZonedDateTime.parse(value, RFC850_FULL_YEAR).toInstant();
        } catch (DateTimeParseException ignored) {
            // try asctime below
        }
        try {
            return ZonedDateTime.parse(value, ASCTIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
