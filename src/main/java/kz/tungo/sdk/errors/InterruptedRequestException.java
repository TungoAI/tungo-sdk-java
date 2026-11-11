package kz.tungo.sdk.errors;

/**
 * The calling thread was interrupted while a request, a retry delay or a stream read was in progress. The interrupt flag of the thread is set again before this exception is thrown; the cause is the original InterruptedException when there is one.
 */
public class InterruptedRequestException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public InterruptedRequestException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public InterruptedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
