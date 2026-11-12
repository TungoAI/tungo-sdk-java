package kz.tungo.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Description of one API request, produced by the generated resource methods. Internal: not part
 * of the public API.
 */
public final class RequestSpec {
    /** How an operation authenticates. */
    public enum Auth {
        /** The API key is sent in {@code x-api-key}. */
        KEY,
        /** No credentials are sent. */
        NONE
    }

    final String method;
    /** The path template of the operation: the only form of the path shown in messages. */
    final String route;
    final String path;
    Auth auth = Auth.KEY;
    String query = "";
    byte[] body;
    String contentType;
    String idempotencyHeader;
    String defaultIdempotencyKey;
    boolean longRunning;
    final Map<String, String> headers = new LinkedHashMap<>();

    private RequestSpec(String method, String route, String path) {
        this.method = method;
        this.route = route;
        this.path = path;
    }

    /**
     * Starts a request description.
     *
     * @param method HTTP method
     * @param route path template of the operation, such as {@code /hooks/{token}}; error messages
     *     show it instead of the path, whose parameters may be secrets
     * @param path path with encoded parameters, starting with a slash
     * @return a new description
     */
    public static RequestSpec of(String method, String route, String path) {
        return new RequestSpec(method, route, path);
    }

    /**
     * Sets how the operation authenticates.
     *
     * @param auth the authentication mode
     * @return this description
     */
    public RequestSpec auth(Auth auth) {
        this.auth = auth;
        return this;
    }

    /**
     * Sets the query string.
     *
     * @param query the query parameters
     * @return this description
     */
    public RequestSpec query(QueryString query) {
        this.query = query.toString();
        return this;
    }

    /**
     * Sets a JSON body.
     *
     * @param value the body model; null sends no body
     * @return this description
     */
    public RequestSpec jsonBody(Object value) {
        if (value != null) {
            this.body = Json.encode(value);
            this.contentType = "application/json";
        }
        return this;
    }

    /**
     * Sets a JSON object body with the {@code stream} property forced to a value.
     *
     * @param value the body model
     * @param stream the value of the {@code stream} property
     * @return this description
     * @throws ValidationException when the body is null or does not serialize to a JSON object
     */
    public RequestSpec jsonBodyWithStream(Object value, boolean stream) {
        if (value == null) {
            throw new ValidationException("The request body must not be null");
        }
        JsonNode tree = Json.toTree(value);
        if (!tree.isObject()) {
            throw new ValidationException("The request body must be a JSON object");
        }
        ((ObjectNode) tree).put("stream", stream);
        this.body = Json.encode(tree);
        this.contentType = "application/json";
        return this;
    }

    /**
     * Sets a body of arbitrary bytes.
     *
     * @param bytes the exact bytes to send; null sends no body
     * @param contentType the media type, may be null
     * @return this description
     */
    public RequestSpec rawBody(byte[] bytes, String contentType) {
        this.body = bytes == null ? null : bytes.clone();
        this.contentType = contentType;
        return this;
    }

    /**
     * Declares that the operation accepts an idempotency key in the given header; the SDK then
     * generates a key when the caller does not supply one.
     *
     * @param headerName the header name, for example {@code Idempotency-Key}
     * @return this description
     */
    public RequestSpec idempotent(String headerName) {
        this.idempotencyHeader = headerName;
        return this;
    }

    /**
     * Sets the idempotency key used when the caller does not supply one, instead of a random key:
     * the event id of a hook event, because the API reads {@code Idempotency-Key} before
     * {@code X-Event-Id} and a random key would override the event id.
     *
     * @param key the default key; null or empty keeps the random key
     * @return this description
     */
    public RequestSpec defaultIdempotencyKey(String key) {
        this.defaultIdempotencyKey = key == null || key.isEmpty() ? null : key;
        return this;
    }

    /**
     * Marks the operation as long-running: its default timeout is 600 seconds.
     *
     * @return this description
     */
    public RequestSpec longRunning() {
        this.longRunning = true;
        return this;
    }

    /**
     * Adds a request header declared by the operation. A null value is skipped.
     *
     * @param name header name
     * @param value header value, may be null
     * @return this description
     */
    public RequestSpec header(String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
        return this;
    }
}
