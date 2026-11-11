package kz.tungo.sdk.errors;

/**
 * The request, a stream or a waiting helper did not finish within its time limit.
 */
public class TungoTimeoutException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public TungoTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public TungoTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
