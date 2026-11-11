package kz.tungo.sdk.errors;

/**
 * API error of type {@code gone_error} (usually HTTP 410): the resource existed but is no longer available.
 */
public class GoneException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public GoneException(Details details) {
        super(details);
    }
}
