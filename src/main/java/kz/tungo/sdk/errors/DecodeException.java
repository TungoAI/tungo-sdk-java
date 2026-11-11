package kz.tungo.sdk.errors;

import java.util.Optional;

/**
 * A successful response (or a stream event) carried a body that is not valid JSON or does not
 * match the expected shape. The request is not retried.
 */
public class DecodeException extends TungoException {
    private static final long serialVersionUID = 1L;

    /** HTTP status code of the response. */
    private final int statusCode;
    /** Request id, or null. */
    private final String requestId;
    /** Raw response body. */
    private final String rawBody;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     * @param cause the parser failure, may be null
     * @param statusCode HTTP status of the response, 0 when unknown
     * @param requestId the {@code x-request-id} of the response, may be null
     * @param rawBody the text that could not be decoded, may be null
     */
    public DecodeException(String message, Throwable cause, int statusCode, String requestId, String rawBody) {
        super(message, cause);
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.rawBody = rawBody;
    }

    /**
     * Creates the exception without response details.
     *
     * @param message human-readable description
     * @param cause the parser failure, may be null
     */
    public DecodeException(String message, Throwable cause) {
        this(message, cause, 0, null, null);
    }

    /**
     * Returns the HTTP status of the response.
     *
     * @return the status code, 0 when unknown
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the request id of the response.
     *
     * @return the {@code x-request-id} header value, if any
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Returns the text that could not be decoded.
     *
     * @return the raw body, if it was read
     */
    public Optional<String> rawBody() {
        return Optional.ofNullable(rawBody);
    }
}
