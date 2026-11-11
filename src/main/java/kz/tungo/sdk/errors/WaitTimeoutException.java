package kz.tungo.sdk.errors;

/**
 * A polling helper (waitForRun, waitForBatch, runScenarioAndWait) reached its overall deadline before the resource reached a final state. This is a local deadline, not an HTTP 408.
 */
public class WaitTimeoutException extends TungoTimeoutException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public WaitTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public WaitTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
