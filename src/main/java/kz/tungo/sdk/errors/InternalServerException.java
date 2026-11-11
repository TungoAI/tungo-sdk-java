package kz.tungo.sdk.errors;

/**
 * API error of type {@code api_error} (usually HTTP 500): the API failed while handling the request.
 */
public class InternalServerException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public InternalServerException(Details details) {
        super(details);
    }
}
