package kz.tungo.sdk.errors;

/**
 * API error of type {@code not_found_error} (usually HTTP 404): the resource does not exist.
 */
public class NotFoundException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public NotFoundException(Details details) {
        super(details);
    }
}
