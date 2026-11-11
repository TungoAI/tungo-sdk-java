package kz.tungo.sdk;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kz.tungo.sdk.errors.ValidationException;
import kz.tungo.sdk.models.WebhookEvent;

/**
 * Static helpers for outgoing webhooks (Standard Webhooks / Svix signatures) and for the
 * signature of incoming hooks ({@code X-Signature: sha256=<hex>}).
 */
public final class TungoWebhooks {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TungoWebhooks() {
    }

    /**
     * Verifies a webhook with the default tolerance (300 s) against the system clock and returns
     * the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers ({@code webhook-*} or {@code svix-*}); names are
     *     case-insensitive, values may be strings or collections of strings
     * @param secret the endpoint secret ({@code whsec_...})
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     */
    public static WebhookEvent verify(String body, Map<String, ?> headers, String secret) {
        return WebhookVerifier.of(secret).verify(body, headers);
    }

    /**
     * Verifies a webhook with the default tolerance (300 s) against the system clock and returns
     * the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headerLookup returns the value of a header by name (for example
     *     {@code request::getHeader}); must be case-insensitive
     * @param secret the endpoint secret ({@code whsec_...})
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     */
    public static WebhookEvent verify(String body, Function<String, String> headerLookup, String secret) {
        return WebhookVerifier.of(secret).verify(body, headerLookup);
    }

    /**
     * Verifies a webhook from the bytes of its body, with the default tolerance (300 s) against
     * the system clock, and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers ({@code webhook-*} or {@code svix-*}); names are
     *     case-insensitive, values may be strings or collections of strings
     * @param secret the endpoint secret ({@code whsec_...})
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     */
    public static WebhookEvent verify(byte[] body, Map<String, ?> headers, String secret) {
        return WebhookVerifier.of(secret).verify(body, headers);
    }

    /**
     * Computes the signature of an incoming-hook body: HMAC-SHA256 with the secret's UTF-8 bytes,
     * as lower-case hex.
     *
     * @param body the exact bytes that are sent
     * @param secret the hook secret
     * @return 64 lower-case hex characters
     * @throws ValidationException when the secret is null or empty
     */
    public static String hookSignatureHex(byte[] body, String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new ValidationException("The hook secret must not be empty");
        }
        if (body == null) {
            throw new ValidationException("The hook body must not be null");
        }
        return hex(hmacSha256(secret.getBytes(StandardCharsets.UTF_8), body));
    }

    /**
     * Computes the value of the {@code X-Signature} header of an incoming hook.
     *
     * @param body the exact bytes that are sent
     * @param secret the hook secret
     * @return {@code sha256=} followed by the lower-case hex signature
     * @throws ValidationException when the secret is null or empty
     */
    public static String signHook(byte[] body, String secret) {
        return "sha256=" + hookSignatureHex(body, secret);
    }

    /**
     * Checks the signature of a hook body in constant time.
     *
     * @param body the exact bytes received
     * @param signature the signature: hex in any case, with or without the {@code sha256=} prefix
     * @param secret the hook secret
     * @return true when the signature matches; false for a wrong, malformed or missing signature
     *     and for an empty secret
     */
    public static boolean verifyHookSignature(byte[] body, String signature, String secret) {
        if (body == null || signature == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String given = signature;
        if (given.length() >= 7 && given.substring(0, 7).toLowerCase(Locale.ROOT).equals("sha256=")) {
            given = given.substring(7);
        }
        byte[] got = unhex(given);
        if (got == null || got.length != 32) {
            return false;
        }
        return MessageDigest.isEqual(got, hmacSha256(secret.getBytes(StandardCharsets.UTF_8), body));
    }

    static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available on this JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0x0F];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    private static byte[] unhex(String text) {
        if (text.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16);
            int lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0 || text.charAt(i * 2) > 'f' || text.charAt(i * 2 + 1) > 'f') {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
