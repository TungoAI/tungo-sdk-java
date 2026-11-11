package kz.tungo.sdk.errors;

/**
 * API error of type {@code conflict_error} (usually HTTP 409): the request conflicts with the current state of the resource.
 */
public class ConflictException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public ConflictException(Details details) {
        super(details);
    }
}
