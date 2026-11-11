package kz.tungo.sdk.errors;

/**
 * API error of type {@code payment_required_error} (usually HTTP 402): the balance or the plan does not allow the request.
 */
public class PaymentRequiredException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    public PaymentRequiredException(Details details) {
        super(details);
    }
}
