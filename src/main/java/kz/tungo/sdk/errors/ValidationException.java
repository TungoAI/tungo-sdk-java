package kz.tungo.sdk.errors;

/**
 * An argument is invalid, for example an empty path parameter or a malformed amount. Thrown locally, before any request is made.
 */
public class ValidationException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public ValidationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
