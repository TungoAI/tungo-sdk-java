package kz.tungo.sdk.internal;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The resolved, immutable configuration of a client. Internal: not part of the public API.
 */
public final class ClientConfig {
    /** Default timeout of an ordinary request. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** Default timeout of a non-streaming chat turn. */
    public static final Duration DEFAULT_LONG_TIMEOUT = Duration.ofSeconds(600);
    /** Default connect timeout of the built-in HTTP client. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** Default longest pause without bytes on a stream. */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(120);
    /** Default size of the thread pool of the async client. */
    public static final int DEFAULT_ASYNC_MAX_THREADS = 64;
    /** Default number of retries. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** API key, null when not configured. */
    public final String apiKey;
    /** Base URL without a trailing slash. */
    public final String baseUrl;
    /** Webhook signing secret, null when not configured. */
    public final String webhookSecret;
    /** Request timeout set on the client, null for the defaults. */
    public final Duration timeout;
    /** Connect timeout of the built-in HTTP client. */
    public final Duration connectTimeout;
    /** Longest pause without bytes on a stream. */
    public final Duration streamIdleTimeout;
    /** Maximum number of retries. */
    public final int maxRetries;
    /** Headers added to every request. */
    public final Map<String, String> defaultHeaders;
    /** Caller-supplied HTTP client, null to build one. */
    public final HttpClient httpClient;
    /** Caller-supplied executor of the async client, null to build one. */
    public final Executor asyncExecutor;
    /** Largest number of threads of the built-in async pool. */
    public final int asyncMaxThreads;
    /** Clock used for HTTP-date headers and webhook timestamps. */
    public final Clock clock;

    /**
     * Creates a configuration.
     *
     * @param apiKey API key, may be null
     * @param baseUrl base URL without a trailing slash
     * @param webhookSecret webhook secret, may be null
     * @param timeout request timeout, null for the defaults
     * @param connectTimeout connect timeout of the built-in HTTP client
     * @param streamIdleTimeout stream idle timeout
     * @param maxRetries maximum number of retries
     * @param defaultHeaders headers added to every request
     * @param httpClient caller-supplied HTTP client, may be null
     * @param asyncExecutor caller-supplied executor, may be null
     * @param asyncMaxThreads largest number of threads of the built-in async pool
     * @param clock the clock
     */
    public ClientConfig(String apiKey, String baseUrl, String webhookSecret, Duration timeout, Duration connectTimeout,
        Duration streamIdleTimeout, int maxRetries, Map<String, String> defaultHeaders, HttpClient httpClient,
        Executor asyncExecutor, int asyncMaxThreads, Clock clock) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret;
        this.timeout = timeout;
        this.connectTimeout = connectTimeout;
        this.streamIdleTimeout = streamIdleTimeout;
        this.maxRetries = maxRetries;
        this.defaultHeaders = defaultHeaders;
        this.httpClient = httpClient;
        this.asyncExecutor = asyncExecutor;
        this.asyncMaxThreads = asyncMaxThreads;
        this.clock = clock;
    }
}
