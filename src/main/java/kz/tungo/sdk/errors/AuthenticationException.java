package kz.tungo.sdk.errors;

/**
 * API error of type {@code authentication_error} (usually HTTP 401): the API key is missing, invalid or revoked.
 */
public class AuthenticationException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public AuthenticationException(Details details) {
        super(details);
    }
}
