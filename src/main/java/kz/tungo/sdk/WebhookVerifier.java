package kz.tungo.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.WebhookVerificationException;
import kz.tungo.sdk.errors.WebhookVerificationException.Reason;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.models.WebhookEvent;

/**
 * Verifies outgoing webhooks signed according to the Standard Webhooks specification (Svix).
 *
 * <p>The signed content is {@code <id>.<timestamp>.<body>}; the key is the base64-decoded part of
 * the secret after {@code whsec_}; the signature header may carry several space-separated
 * {@code v1,<base64>} signatures. Both the {@code webhook-*} and the {@code svix-*} header names
 * are accepted. The timestamp must be within the tolerance of the clock in both directions.
 * Instances are immutable.
 */
public final class WebhookVerifier {
    /** The default timestamp tolerance: 300 seconds. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    private WebhookVerifier(String secret, Duration tolerance, Clock clock) {
        this.secret = secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /**
     * Creates a verifier with the default tolerance and the system clock.
     *
     * @param secret the endpoint secret ({@code whsec_...}); an empty or malformed secret makes
     *     every verification fail with a typed error
     * @return a verifier
     */
    public static WebhookVerifier of(String secret) {
        return new WebhookVerifier(secret, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /**
     * Returns a verifier with another timestamp tolerance.
     *
     * @param tolerance the tolerance; zero or negative makes every verification fail (it does not
     *     disable the check)
     * @return a new verifier
     */
    public WebhookVerifier withTolerance(Duration tolerance) {
        return new WebhookVerifier(secret, Objects.requireNonNull(tolerance, "tolerance"), clock);
    }

    /**
     * Returns a verifier with another clock (for tests).
     *
     * @param clock the clock the timestamp is compared with
     * @return a new verifier
     */
    public WebhookVerifier withClock(Clock clock) {
        return new WebhookVerifier(secret, tolerance, Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Verifies a webhook and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers; names are case-insensitive, values may be strings,
     *     collections of strings or string arrays
     * @return the verified event
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verify(String body, Map<String, ?> headers) {
        if (headers == null) {
            throw new WebhookVerificationException(Reason.MISSING_HEADER, "The webhook headers are missing");
        }
        return verify(body, name -> lookup(headers, name));
    }

    /**
     * Verifies a webhook and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers
     * @return the verified event
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verify(String body, java.net.http.HttpHeaders headers) {
        if (headers == null) {
            throw new WebhookVerificationException(Reason.MISSING_HEADER, "The webhook headers are missing");
        }
        return verify(body, name -> headers.firstValue(name).orElse(null));
    }

    /**
     * Verifies a webhook and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headerLookup returns the value of a header by name, or null; must be
     *     case-insensitive (for example {@code request::getHeader})
     * @return the verified event
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verify(String body, Function<String, String> headerLookup) {
        return verify(body == null ? null : body.getBytes(StandardCharsets.UTF_8), headerLookup);
    }

    /**
     * Verifies a webhook from the bytes of its body and returns the typed event. Prefer this
     * form when the framework hands out the raw bytes: the signature covers the bytes as sent,
     * and a body that went through a charset conversion may no longer match.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers; names are case-insensitive, values may be strings,
     *     collections of strings or string arrays
     * @return the verified event
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verify(byte[] body, Map<String, ?> headers) {
        if (headers == null) {
            throw new WebhookVerificationException(Reason.MISSING_HEADER, "The webhook headers are missing");
        }
        return verify(body, name -> lookup(headers, name));
    }

    /**
     * Verifies a webhook from the bytes of its body and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headerLookup returns the value of a header by name, or null; must be
     *     case-insensitive (for example {@code request::getHeader})
     * @return the verified event
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verify(byte[] body, Function<String, String> headerLookup) {
        if (tolerance.isZero() || tolerance.isNegative()) {
            throw new WebhookVerificationException(Reason.INVALID_TOLERANCE, "The webhook tolerance must be positive");
        }
        byte[] key = decodeSecret(secret);
        if (body == null || headerLookup == null) {
            throw new WebhookVerificationException(Reason.MISSING_HEADER, "The webhook body and headers are required");
        }
        String id = header(headerLookup, "id");
        String timestamp = header(headerLookup, "timestamp");
        String signatures = header(headerLookup, "signature");
        if (id == null || timestamp == null || signatures == null) {
            throw new WebhookVerificationException(Reason.MISSING_HEADER,
                "The webhook-id, webhook-timestamp and webhook-signature headers are required");
        }
        long sent;
        try {
            if (!DIGITS.matcher(timestamp).matches()) {
                throw new NumberFormatException();
            }
            sent = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException(Reason.MALFORMED_TIMESTAMP,
                "The webhook timestamp is not a whole number of seconds");
        }
        long now = clock.instant().getEpochSecond();
        long allowed = tolerance.getSeconds();
        if (now - sent > allowed) {
            throw new WebhookVerificationException(Reason.TIMESTAMP_TOO_OLD, "The webhook timestamp is too old");
        }
        if (sent - now > allowed) {
            throw new WebhookVerificationException(Reason.TIMESTAMP_TOO_NEW, "The webhook timestamp is too far in the future");
        }
        byte[] prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        byte[] expected = TungoWebhooks.hmacSha256(key, signed);
        boolean matched = false;
        for (String part : signatures.trim().split("\\s+")) {
            int comma = part.indexOf(',');
            if (comma < 0 || !part.substring(0, comma).equals("v1")) {
                continue;
            }
            byte[] candidate;
            try {
                candidate = Base64.getDecoder().decode(part.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(candidate, expected)) {
                matched = true;
            }
        }
        if (!matched) {
            throw new WebhookVerificationException(Reason.SIGNATURE_MISMATCH, "No webhook signature matches the payload");
        }
        try {
            return Json.decodeText(new String(body, StandardCharsets.UTF_8), WebhookEvent.class, "the webhook payload");
        } catch (DecodeException e) {
            throw new WebhookVerificationException(Reason.INVALID_PAYLOAD,
                "The webhook signature is valid but the payload is not a webhook event", e);
        }
    }

    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new WebhookVerificationException(Reason.INVALID_SECRET, "The webhook secret is not set");
        }
        String encoded = secret.startsWith("whsec_") ? secret.substring(6) : secret;
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException(Reason.INVALID_SECRET, "The webhook secret is not valid base64");
        }
        if (key.length == 0) {
            throw new WebhookVerificationException(Reason.INVALID_SECRET, "The webhook secret is empty");
        }
        return key;
    }

    private static String header(Function<String, String> lookup, String suffix) {
        String value = lookup.apply("svix-" + suffix);
        if (value == null) {
            value = lookup.apply("webhook-" + suffix);
        }
        return value;
    }

    private static String lookup(Map<String, ?> headers, String name) {
        for (Map.Entry<String, ?> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                Object value = entry.getValue();
                if (value instanceof Collection) {
                    Collection<?> values = (Collection<?>) value;
                    value = values.isEmpty() ? null : values.iterator().next();
                } else if (value instanceof Object[]) {
                    Object[] values = (Object[]) value;
                    value = values.length == 0 ? null : values[0];
                }
                if (value != null) {
                    return value.toString();
                }
            }
        }
        return null;
    }
}
