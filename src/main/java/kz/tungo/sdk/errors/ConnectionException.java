package kz.tungo.sdk.errors;

/**
 * The request could not be sent or the connection failed before a complete response arrived.
 */
public class ConnectionException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public ConnectionException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
