package kz.tungo.sdk;

import java.net.http.HttpHeaders;
import java.util.Optional;

/**
 * A decoded response together with its HTTP details: status, headers, request id and the
 * rate-limit information of this very response.
 *
 * @param <T> type of the decoded body; {@link Void} for responses without a body
 */
public final class ApiResponse<T> {
    private final int statusCode;
    private final HttpHeaders headers;
    private final T body;

    /**
     * Creates a response.
     *
     * @param statusCode HTTP status
     * @param headers response headers
     * @param body decoded body, null for responses without a body
     */
    public ApiResponse(int statusCode, HttpHeaders headers, T body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the response headers.
     *
     * @return the headers (names are case-insensitive)
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the first value of a response header.
     *
     * @param name header name, case-insensitive
     * @return the value, if the header is present
     */
    public Optional<String> header(String name) {
        return headers.firstValue(name);
    }

    /**
     * Returns the decoded body.
     *
     * @return the body; null for responses without a body
     */
    public T body() {
        return body;
    }

    /**
     * Returns the request id assigned by the API ({@code x-request-id}).
     *
     * @return the request id, if the header is present
     */
    public Optional<String> requestId() {
        return headers.firstValue("x-request-id");
    }

    /**
     * Returns the rate-limit information of this response.
     *
     * @return the rate limit, if the API sent the {@code x-ratelimit-*} headers
     */
    public Optional<RateLimit> rateLimit() {
        return RateLimit.from(headers);
    }

    /**
     * Tells whether the API replayed a stored response for a repeated idempotency key.
     *
     * @return true when the {@code Idempotent-Replayed} header is {@code true}
     */
    public boolean idempotentReplayed() {
        return headers.firstValue("Idempotent-Replayed").map("true"::equalsIgnoreCase).orElse(false);
    }

    /**
     * Returns a copy of this response with another body.
     *
     * @param newBody the new body
     * @param <R> type of the new body
     * @return a response with the same status and headers
     */
    public <R> ApiResponse<R> withBody(R newBody) {
        return new ApiResponse<>(statusCode, headers, newBody);
    }

    @Override
    public String toString() {
        return "ApiResponse{statusCode=" + statusCode + ", requestId=" + requestId().orElse(null) + ", body=" + body + "}";
    }
}
