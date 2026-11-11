package kz.tungo.sdk.errors;

/**
 * A webhook could not be verified: the secret, the headers, the timestamp or the signature is
 * missing or wrong. The payload must not be trusted.
 */
public class WebhookVerificationException extends TungoException {
    private static final long serialVersionUID = 1L;

    /** Why the verification failed. */
    public enum Reason {
        /** The tolerance is zero or negative. */
        INVALID_TOLERANCE,
        /** The secret is empty or is not valid base64. */
        INVALID_SECRET,
        /** The id, timestamp or signature header is missing. */
        MISSING_HEADER,
        /** The timestamp header is not a whole number of seconds. */
        MALFORMED_TIMESTAMP,
        /** The message is older than the tolerance allows. */
        TIMESTAMP_TOO_OLD,
        /** The message is dated further in the future than the tolerance allows. */
        TIMESTAMP_TOO_NEW,
        /** No signature in the header matches the payload. */
        SIGNATURE_MISMATCH,
        /** The signature is valid but the payload is not a webhook event. */
        INVALID_PAYLOAD
    }

    /** Why the verification failed. */
    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the verification failed
     * @param message human-readable description
     */
    public WebhookVerificationException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param reason why the verification failed
     * @param message human-readable description
     * @param cause the underlying failure, may be null
     */
    public WebhookVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Returns why the verification failed.
     *
     * @return the reason code
     */
    public Reason reason() {
        return reason;
    }
}
