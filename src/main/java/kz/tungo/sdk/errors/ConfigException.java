package kz.tungo.sdk.errors;

/**
 * The client is configured incorrectly, for example an authenticated operation was called without an API key. Thrown locally, before any request is made.
 */
public class ConfigException extends TungoException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human-readable description
     */
    public ConfigException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the original cause.
     *
     * @param message human-readable description
     * @param cause the underlying failure, kept in the cause chain
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
