package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import kz.tungo.sdk.errors.ApiException;
import kz.tungo.sdk.errors.AuthenticationException;
import kz.tungo.sdk.errors.ConfigException;
import kz.tungo.sdk.errors.ConflictException;
import kz.tungo.sdk.errors.ConnectionException;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.GoneException;
import kz.tungo.sdk.errors.InternalServerException;
import kz.tungo.sdk.errors.InterruptedRequestException;
import kz.tungo.sdk.errors.InvalidRequestException;
import kz.tungo.sdk.errors.LimitException;
import kz.tungo.sdk.errors.NotFoundException;
import kz.tungo.sdk.errors.PaginationException;
import kz.tungo.sdk.errors.PaymentRequiredException;
import kz.tungo.sdk.errors.PermissionDeniedException;
import kz.tungo.sdk.errors.TungoException;
import kz.tungo.sdk.errors.TungoTimeoutException;
import kz.tungo.sdk.errors.UnavailableException;
import kz.tungo.sdk.errors.ValidationException;
import kz.tungo.sdk.errors.WaitTimeoutException;
import kz.tungo.sdk.errors.WebhookVerificationException;
import org.junit.jupiter.api.Test;

class ErrorsTest {
    static final Map<String, Class<? extends ApiException>> CLASSES = new LinkedHashMap<>();

    static {
        CLASSES.put("invalid_request_error", InvalidRequestException.class);
        CLASSES.put("authentication_error", AuthenticationException.class);
        CLASSES.put("payment_required_error", PaymentRequiredException.class);
        CLASSES.put("permission_error", PermissionDeniedException.class);
        CLASSES.put("not_found_error", NotFoundException.class);
        CLASSES.put("conflict_error", ConflictException.class);
        CLASSES.put("gone_error", GoneException.class);
        CLASSES.put("limit_error", LimitException.class);
        CLASSES.put("api_error", InternalServerException.class);
        CLASSES.put("unavailable_error", UnavailableException.class);
    }

    @Test
    void theHierarchyHasOneUncheckedRoot() {
        assertTrue(RuntimeException.class.isAssignableFrom(TungoException.class));
        for (Class<?> type : new Class<?>[] {ApiException.class, ConnectionException.class, TungoTimeoutException.class,
            DecodeException.class, ConfigException.class, ValidationException.class, PaginationException.class,
            InterruptedRequestException.class, WebhookVerificationException.class, WaitTimeoutException.class}) {
            assertTrue(TungoException.class.isAssignableFrom(type), type.getName());
        }
        for (Class<?> type : CLASSES.values()) {
            assertSame(ApiException.class, type.getSuperclass(), type.getName());
        }
        assertTrue(TungoTimeoutException.class.isAssignableFrom(WaitTimeoutException.class));
        assertFalse(ApiException.class.isAssignableFrom(WaitTimeoutException.class));
    }

    @Test
    void theClassIsChosenByTheErrorTypeOfTheBody() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            for (Map.Entry<String, Class<? extends ApiException>> entry : CLASSES.entrySet()) {
                server.enqueueJson(418, TestSupport.envelope(entry.getKey(), "some_code", "Сообщение"));
                ApiException e = assertThrows(ApiException.class, () -> client.companies().list());
                assertSame(entry.getValue(), e.getClass(), entry.getKey());
                assertEquals(entry.getKey(), e.type());
                assertEquals(418, e.statusCode());
                assertTrue(e.isEnvelope());
            }
        }
    }

    @Test
    void exposesEveryFieldOfTheEnvelope() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            String body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":\"validation_failed\","
                + "\"message\":\"Проверьте поля запроса.\",\"fields\":[{\"path\":\"bin\",\"code\":\"invalid_format\","
                + "\"message\":\"БИН/ИИН — 12 цифр\"}],\"resets_at\":\"2026-11-09T18:00:00.000Z\",\"brand_new\":7},"
                + "\"request_id\":\"req_body_1\"}";
            server.enqueueJson(400, body, "x-request-id", "req_header_1", "Retry-After", "1.5", "X-Custom", "yes");
            InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> client.companies().list());
            assertEquals(400, e.statusCode());
            assertEquals("invalid_request_error", e.type());
            assertEquals("validation_failed", e.code().orElseThrow());
            assertEquals("Проверьте поля запроса.", e.errorMessage().orElseThrow());
            assertEquals("req_body_1", e.requestId().orElseThrow());
            assertEquals(1, e.fields().size());
            assertEquals("bin", e.fields().get(0).path());
            assertEquals("invalid_format", e.fields().get(0).code().value());
            assertEquals(1.5, e.retryAfterSeconds().orElseThrow(), 0.0);
            assertEquals("yes", e.headers().firstValue("x-custom").orElseThrow());
            assertEquals(body, e.rawBody());
            assertTrue(e.detail().isPresent());
            assertEquals(7, e.extra().get("brand_new").asInt());
            assertTrue(e.extra().containsKey("resets_at") || e.detail().orElseThrow().toString().contains("resets_at"));
            String message = e.getMessage();
            assertTrue(message.contains("400"), message);
            assertTrue(message.contains("validation_failed"), message);
            assertTrue(message.contains("req_body_1"), message);
            assertTrue(message.contains("Проверьте поля запроса."), message);
        }
    }

    @Test
    void theRequestIdFallsBackToTheHeader() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            server.enqueueJson(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"Нет\"},\"request_id\":null}",
                "X-Request-Id", "req_from_header");
            NotFoundException e = assertThrows(NotFoundException.class, () -> client.companies().get("co_x"));
            assertEquals("req_from_header", e.requestId().orElseThrow());
            assertFalse(e.code().isPresent());
            assertTrue(e.fields().isEmpty());
        }
    }

    @Test
    void htmlAndEmptyBodiesFallBackToTheStatus() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            server.enqueue((request, exchange) ->
                exchange.respond(502, "text/html", "<html><h1>502 Bad Gateway</h1></html>".getBytes()));
            UnavailableException html = assertThrows(UnavailableException.class, () -> client.companies().list());
            assertFalse(html.isEnvelope());
            assertEquals("unavailable_error", html.type());
            assertTrue(html.rawBody().contains("502 Bad Gateway"));
            assertTrue(html.getMessage().contains("502"), html.getMessage());

            server.enqueue((request, exchange) -> exchange.respond(500, null, new byte[0]));
            InternalServerException empty = assertThrows(InternalServerException.class, () -> client.companies().list());
            assertEquals("", empty.rawBody());

            server.enqueueJson(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_er");
            assertThrows(AuthenticationException.class, () -> client.companies().list());
            server.enqueueJson(403, "[]");
            assertThrows(PermissionDeniedException.class, () -> client.companies().list());
            server.enqueueJson(410, "{\"message\":\"gone\"}");
            assertThrows(GoneException.class, () -> client.companies().list());
            server.enqueueJson(402, "\"text\"");
            assertThrows(PaymentRequiredException.class, () -> client.companies().list());
        }
    }

    @Test
    void anUnknownErrorTypeIsKeptAndClassifiedByStatus() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.builder(server).maxRetries(0).build()) {
            server.enqueueJson(409, TestSupport.envelope("quantum_error", "entangled", "New"));
            ConflictException e = assertThrows(ConflictException.class, () -> client.companies().list());
            assertEquals("quantum_error", e.type());
            assertEquals("entangled", e.code().orElseThrow());
        }
    }
}
