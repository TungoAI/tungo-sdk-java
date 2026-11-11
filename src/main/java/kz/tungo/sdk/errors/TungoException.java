package kz.tungo.sdk.errors;

/**
 * Base class of every exception thrown by the Tungo SDK.
 *
 * <p>All SDK exceptions are unchecked. Catch this type to handle any SDK failure, or one of its
 * subclasses to handle a specific kind of failure.
 */
public class TungoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message human-readable description
     */
    public TungoException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public TungoException(String message, Throwable cause) {
        super(message, cause);
    }
}
