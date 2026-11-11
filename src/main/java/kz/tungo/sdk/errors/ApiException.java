package kz.tungo.sdk.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.internal.RetryPolicy;
import kz.tungo.sdk.models.ErrorDetail;
import kz.tungo.sdk.models.FieldError;

/**
 * The API answered with an error status (4xx or 5xx), or a stream delivered an error event.
 *
 * <p>The concrete subclass is chosen by the {@code error.type} field of the response body
 * ({@link InvalidRequestException}, {@link AuthenticationException}, {@link PaymentRequiredException},
 * {@link PermissionDeniedException}, {@link NotFoundException}, {@link ConflictException},
 * {@link GoneException}, {@link LimitException}, {@link InternalServerException},
 * {@link UnavailableException}). When the body is not the API's error envelope the class is chosen
 * by the HTTP status.
 */
public class ApiException extends TungoException {
    private static final long serialVersionUID = 1L;

    /** HTTP status code of the response. */
    private final int statusCode;
    /** Error type of the contract. */
    private final String type;
    /** Machine-readable error code, or null. */
    private final String code;
    /** Error message of the server, or null. */
    private final String errorMessage;
    /** Request id, or null. */
    private final String requestId;
    /** Delay the server asked for in seconds, or null. */
    private final Double retryAfterSeconds;
    /** Raw response body. */
    private final String rawBody;
    /** Whether the body was the error envelope of the contract. */
    private final boolean envelope;
    private final transient HttpHeaders headers;
    private final transient ErrorDetail detail;
    private final transient List<FieldError> fields;
    private final transient Map<String, JsonNode> extra;

    /**
     * Creates the exception from parsed response details.
     *
     * @param details the parsed error response
     */
    protected ApiException(Details details) {
        super(details.describe());
        this.statusCode = details.statusCode;
        this.type = details.type;
        this.code = details.code;
        this.errorMessage = details.message;
        this.requestId = details.requestId;
        this.retryAfterSeconds = details.retryAfterSeconds;
        this.rawBody = details.rawBody;
        this.envelope = details.envelope;
        this.headers = details.headers;
        this.detail = details.detail;
        this.fields = Collections.unmodifiableList(new ArrayList<>(details.fields));
        this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(details.extra));
    }

    /**
     * Returns the HTTP status of the response. For an error event delivered inside a stream this
     * is the status of the stream response (normally 200).
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the error type: {@code error.type} exactly as the API sent it, or the type derived
     * from the HTTP status when the body is not the error envelope.
     *
     * @return the error type, for example {@code not_found_error}
     */
    public String type() {
        return type;
    }

    /**
     * Returns the machine-readable error code ({@code error.code}).
     *
     * @return the code, if the API sent one
     */
    public Optional<String> code() {
        return Optional.ofNullable(code);
    }

    /**
     * Returns the message of the API ({@code error.message}), without status, code or request id.
     *
     * @return the message, empty when the body is not the error envelope
     */
    public Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    /**
     * Returns the request id: {@code request_id} of the body, otherwise the {@code x-request-id}
     * header. Quote it when contacting support.
     *
     * @return the request id, if known
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Returns the per-field validation errors ({@code error.fields}).
     *
     * @return the field errors, empty when there are none
     */
    public List<FieldError> fields() {
        return fields == null ? Collections.emptyList() : fields;
    }

    /**
     * Returns the delay the server asked for in {@code retry-after-ms} or {@code Retry-After}.
     *
     * @return the delay, if the response carried a usable value
     */
    public Optional<Duration> retryAfter() {
        if (retryAfterSeconds == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.round(retryAfterSeconds * 1_000_000_000d)));
    }

    /**
     * Returns the delay the server asked for, in seconds.
     *
     * @return the delay in seconds (fractions allowed), if the response carried a usable value
     */
    public Optional<Double> retryAfterSeconds() {
        return Optional.ofNullable(retryAfterSeconds);
    }

    /**
     * Returns the response headers.
     *
     * @return the headers; empty headers after Java deserialization of the exception
     */
    public HttpHeaders headers() {
        return headers == null ? RetryPolicy.EMPTY_HEADERS : headers;
    }

    /**
     * Returns the response body exactly as received.
     *
     * @return the raw body text, possibly empty
     */
    public String rawBody() {
        return rawBody == null ? "" : rawBody;
    }

    /**
     * Tells whether the body was the API's error envelope.
     *
     * @return true when {@code error.type} and {@code error.message} were present
     */
    public boolean isEnvelope() {
        return envelope;
    }

    /**
     * Returns the typed {@code error} object of the envelope with every property the contract
     * declares (permission, resets_at, allowed_methods, ...).
     *
     * @return the error detail, empty when the body is not the error envelope
     */
    public Optional<ErrorDetail> detail() {
        return Optional.ofNullable(detail);
    }

    /**
     * Returns every property of the {@code error} object other than type, message, code and
     * fields, as raw JSON values.
     *
     * @return the extra properties by name, possibly empty
     */
    public Map<String, JsonNode> extra() {
        return extra == null ? Collections.emptyMap() : extra;
    }

    /**
     * Builds the exception for an HTTP error response using the system clock.
     *
     * @param statusCode HTTP status
     * @param headers response headers
     * @param body response body text, may be null or not JSON
     * @return the exception of the matching subclass
     */
    public static ApiException from(int statusCode, HttpHeaders headers, String body) {
        return from(statusCode, headers, body, Clock.systemUTC());
    }

    /**
     * Builds the exception for an HTTP error response.
     *
     * @param statusCode HTTP status
     * @param headers response headers
     * @param body response body text, may be null or not JSON
     * @param clock the clock used to interpret an HTTP-date {@code Retry-After}
     * @return the exception of the matching subclass
     */
    public static ApiException from(int statusCode, HttpHeaders headers, String body, Clock clock) {
        Details d = new Details();
        d.statusCode = statusCode;
        d.headers = headers == null ? RetryPolicy.EMPTY_HEADERS : headers;
        d.rawBody = body == null ? "" : body;
        d.type = statusFallback(statusCode);
        d.retryAfterSeconds = RetryPolicy.parseRetryAfter(d.headers, clock.instant());
        d.requestId = d.headers.firstValue("x-request-id").filter(s -> !s.isEmpty()).orElse(null);
        JsonNode root = Json.tryParse(d.rawBody);
        JsonNode error = root == null ? null : root.get("error");
        if (error != null && error.isObject() && isText(error.get("type")) && isText(error.get("message"))) {
            JsonNode rid = root.get("request_id");
            if (isText(rid) && !rid.asText().isEmpty()) {
                d.requestId = rid.asText();
            }
            d.fill(error);
        }
        return create(d);
    }

    /**
     * Builds the exception for an error event delivered inside a stream.
     *
     * @param statusCode HTTP status of the stream response
     * @param headers headers of the stream response, may be null
     * @param error the {@code error} object of the event: type, message, code and extras
     * @param requestId the request id of the event, may be null
     * @return the exception of the matching subclass
     */
    public static ApiException fromStreamError(int statusCode, HttpHeaders headers, JsonNode error, String requestId) {
        Details d = new Details();
        d.statusCode = statusCode;
        d.headers = headers == null ? RetryPolicy.EMPTY_HEADERS : headers;
        d.rawBody = error == null ? "" : error.toString();
        d.type = "api_error";
        d.requestId = requestId != null && !requestId.isEmpty()
            ? requestId
            : d.headers.firstValue("x-request-id").filter(s -> !s.isEmpty()).orElse(null);
        if (error != null && error.isObject()) {
            d.fill(error);
        }
        if (d.message == null) {
            d.message = "The stream reported an error";
        }
        return create(d);
    }

    /**
     * Returns the error type used when the body is not the error envelope.
     *
     * @param statusCode HTTP status
     * @return one of the API's error types
     */
    public static String statusFallback(int statusCode) {
        switch (statusCode) {
            case 401:
                return "authentication_error";
            case 402:
                return "payment_required_error";
            case 403:
                return "permission_error";
            case 404:
                return "not_found_error";
            case 409:
                return "conflict_error";
            case 410:
                return "gone_error";
            case 429:
                return "limit_error";
            case 502:
            case 503:
            case 504:
                return "unavailable_error";
            default:
                return statusCode >= 500 ? "api_error" : "invalid_request_error";
        }
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual();
    }

    private static ApiException create(Details d) {
        String kind = isKnownType(d.type) ? d.type : statusFallback(d.statusCode);
        switch (kind) {
            case "invalid_request_error":
                return new InvalidRequestException(d);
            case "authentication_error":
                return new AuthenticationException(d);
            case "payment_required_error":
                return new PaymentRequiredException(d);
            case "permission_error":
                return new PermissionDeniedException(d);
            case "not_found_error":
                return new NotFoundException(d);
            case "conflict_error":
                return new ConflictException(d);
            case "gone_error":
                return new GoneException(d);
            case "limit_error":
                return new LimitException(d);
            case "unavailable_error":
                return new UnavailableException(d);
            default:
                return new InternalServerException(d);
        }
    }

    private static boolean isKnownType(String type) {
        switch (type) {
            case "invalid_request_error":
            case "authentication_error":
            case "payment_required_error":
            case "permission_error":
            case "not_found_error":
            case "conflict_error":
            case "gone_error":
            case "limit_error":
            case "api_error":
            case "unavailable_error":
                return true;
            default:
                return false;
        }
    }

    /** Parsed error response handed to the exception constructors. Created by the SDK only. */
    public static final class Details {
        int statusCode;
        String type;
        String code;
        String message;
        String requestId;
        Double retryAfterSeconds;
        String rawBody;
        boolean envelope;
        HttpHeaders headers;
        ErrorDetail detail;
        List<FieldError> fields = new ArrayList<>();
        Map<String, JsonNode> extra = new LinkedHashMap<>();

        Details() {
        }

        void fill(JsonNode error) {
            envelope = isText(error.get("type")) && isText(error.get("message"));
            if (isText(error.get("type"))) {
                type = error.get("type").asText();
            }
            if (isText(error.get("message"))) {
                message = error.get("message").asText();
            }
            if (isText(error.get("code"))) {
                code = error.get("code").asText();
            }
            JsonNode list = error.get("fields");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    FieldError field = Json.tryConvert(item, FieldError.class);
                    if (field != null) {
                        fields.add(field);
                    }
                }
            }
            for (Map.Entry<String, JsonNode> entry : error.properties()) {
                String name = entry.getKey();
                if (!name.equals("type") && !name.equals("message") && !name.equals("code") && !name.equals("fields")) {
                    extra.put(name, entry.getValue());
                }
            }
            detail = Json.tryConvert(error, ErrorDetail.class);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(statusCode).append(' ').append(type);
            if (code != null) {
                sb.append(" (").append(code).append(')');
            }
            sb.append(": ");
            if (message != null) {
                sb.append(message);
            } else if (rawBody == null || rawBody.trim().isEmpty()) {
                sb.append("the server returned HTTP ").append(statusCode).append(" with an empty body");
            } else {
                sb.append("the server returned HTTP ").append(statusCode)
                    .append(" with a body that is not a Tungo error envelope");
            }
            if (requestId != null) {
                sb.append(" [request id ").append(requestId).append(']');
            }
            return sb.toString();
        }
    }
}
