package kz.tungo.sdk.errors;

/**
 * API error of type {@code unavailable_error} (usually HTTP 502, 503 or 504): the API or one of its dependencies is temporarily unavailable.
 */
public class UnavailableException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public UnavailableException(Details details) {
        super(details);
    }
}
