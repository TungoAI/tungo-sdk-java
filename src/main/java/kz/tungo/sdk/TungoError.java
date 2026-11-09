package kz.tungo.sdk;

public class TungoError extends RuntimeException {
    private final int status;
    private final String code;
    private final String hint;
    private final String requestId;
    private final Double retryAfter;

    public TungoError(int status, String code, String message, String hint, String requestId, Double retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.hint = hint;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String hint() { return hint; }
    public String requestId() { return requestId; }
    public Double retryAfter() { return retryAfter; }

    public boolean isValidationError()      { return status == 400; }
    public boolean isAuthError()            { return status == 401; }
    public boolean isPaymentRequiredError() { return status == 402; }
    public boolean isPermissionError()      { return status == 403; }
    public boolean isNotFoundError()        { return status == 404; }
    public boolean isConflictError()        { return status == 409; }
    public boolean isRateLimitError()       { return status == 429; }
}
