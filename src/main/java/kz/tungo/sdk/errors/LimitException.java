package kz.tungo.sdk.errors;

/**
 * API error of type {@code limit_error} (usually HTTP 429): a rate limit or a spending cap was reached.
 */
public class LimitException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public LimitException(Details details) {
        super(details);
    }
}
