package kz.tungo.sdk.errors;

/**
 * Auto-pagination cannot continue, for example because the server returned the same cursor twice.
 */
public class PaginationException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public PaginationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public PaginationException(String message, Throwable cause) {
        super(message, cause);
    }
}
