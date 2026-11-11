package kz.tungo.sdk.errors;

/**
 * API error of type {@code permission_error} (usually HTTP 403): the key has no right to perform the operation.
 */
public class PermissionDeniedException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public PermissionDeniedException(Details details) {
        super(details);
    }
}
