package kz.tungo.sdk.errors;

/**
 * API error of type {@code invalid_request_error} (usually HTTP 400 or 422): the request is malformed or failed validation.
 */
public class InvalidRequestException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public InvalidRequestException(Details details) {
        super(details);
    }
}
