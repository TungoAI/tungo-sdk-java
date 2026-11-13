package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import kz.tungo.sdk.errors.ValidationException;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.models.ApproveRequest;
import kz.tungo.sdk.models.AutomationHookPatch;
import kz.tungo.sdk.models.ChatDialogPatch;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.Money;
import kz.tungo.sdk.params.FeedListEventsParams;
import kz.tungo.sdk.params.PlatformListRequestsParams;
import kz.tungo.sdk.params.ProposalsListParams;
import org.junit.jupiter.api.Test;

class RequestBuildingTest {
    private static final String PAGE = "{\"data\":[],\"counts\":{},\"next_cursor\":null}";

    @Test
    void theIdempotencyKeyOfAnApprovalBodyIsTheHeader() {
        // The API prefers the header over the documented body field: a generated header would make
        // a repeat of the same click a new approval.
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, "{\"message\":\"ok\",\"ok\":true,\"repeated\":false}"));
            ApproveRequest body = ApproveRequest.builder().idempotencyKey(" click-7 ").build();
            client.proposals().approve("co_1", "P-1", body);
            client.proposals().approve("co_1", "P-1", body);
            client.proposals().approve("co_1", "P-1", body, RequestOptions.builder().idempotencyKey("own").build());
            client.proposals().approve("co_1", "P-1", ApproveRequest.builder().build());
            assertEquals("click-7", server.request(0).header("idempotency-key"));
            assertEquals("click-7", server.request(1).header("idempotency-key"));
            assertEquals("own", server.request(2).header("idempotency-key"));
            assertEquals(36, server.request(3).header("idempotency-key").length());
        }
    }

    @Test
    void pathParametersAreEncodedAsOneSegment() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, "{\"id\":\"x\",\"permissions\":[],\"messages\":[]}"));
            client.companies().get("a b/c?d#e\r\nсчёт%2F");
            assertEquals("/v1/companies/a%20b%2Fc%3Fd%23e%0D%0A%D1%81%D1%87%D1%91%D1%82%252F", server.request(0).target);
            client.chat().get("co/1", "dlg 2");
            assertEquals("/v1/companies/co%2F1/chat/dlg%202", server.request(1).target);
        }
    }

    @Test
    void emptyAndDotSegmentsAreRejectedBeforeAnyRequest() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            for (String bad : new String[] {"", ".", ".."}) {
                assertThrows(ValidationException.class, () -> client.companies().get(bad), "'" + bad + "'");
                assertThrows(ValidationException.class, () -> client.chat().get("co_1", bad), "'" + bad + "'");
            }
            assertThrows(ValidationException.class, () -> client.companies().get(null));
            assertThrows(ValidationException.class, () -> client.sendHook("..", "{}", null));
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void queryParametersAreTypedAndEncoded() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, PAGE));
            client.proposals().list("co_1", ProposalsListParams.builder()
                .q("a+b c&d").limit(5).dateFrom(LocalDate.of(2026, 1, 31)).status("new").build());
            String query = server.request(0).query();
            assertTrue(query.contains("q=a%2Bb%20c%26d"), query);
            assertTrue(query.contains("limit=5"), query);
            assertTrue(query.contains("date_from=2026-01-31"), query);
            assertTrue(query.contains("status=new"), query);
            assertFalse(query.contains("cursor"), query);
            assertFalse(query.contains("date_to"), query);

            client.platform().listRequests("ws_1", PlatformListRequestsParams.builder()
                .from(OffsetDateTime.parse("2026-11-09T15:00:00+05:00")).statusMin(400).build());
            query = server.request(1).query();
            assertTrue(query.contains("from=2026-11-09T15%3A00%3A00%2B05%3A00"), query);
            assertTrue(query.contains("status_min=400"), query);

            client.proposals().list("co_1");
            assertEquals("/v1/companies/co_1/proposals", server.request(2).target);
        }
    }

    @Test
    void constantQueryFlagsAreSentOnlyWhenRequested() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.sse());
            try (EventStream<?> stream = client.feed().listEvents("co_1", FeedListEventsParams.builder().once(true).build())) {
                assertFalse(stream.iterator().hasNext());
            }
            assertEquals("once=1", server.request(0).query());
            try (EventStream<?> stream = client.feed().listEvents("co_1", FeedListEventsParams.builder().once(false).build())) {
                assertFalse(stream.iterator().hasNext());
            }
            assertEquals("", server.request(1).query());
        }
    }

    @Test
    void bodiesAreTypedJsonWithNullAndAbsentKeptApart() {
        try (MockServer server = new MockServer(); TungoClient client = TestSupport.client(server)) {
            server.always((request, exchange) -> exchange.json(200, "{\"answer\":\"ok\",\"dialog_id\":\"d\",\"turn_no\":1,\"citations\":[],\"errors\":[],\"followups\":[],\"proposals\":[],\"questions\":[]}"));
            client.chat().create("co_1", ChatMessageRequest.builder().message("Привет\n\"мир\"\u0001").build());
            MockServer.Request request = server.request(0);
            assertEquals("POST", request.method);
            assertEquals("application/json", request.header("content-type"));
            JsonNode body = TestSupport.json(request.text());
            assertEquals("Привет\n\"мир\"\u0001", body.get("message").asText());
            assertTrue(request.text().contains("\\u0001"), request.text());
            assertFalse(body.has("dialog_id"), "an unset nullable field is absent");
            assertFalse(body.path("stream").asBoolean(false), "a non-streaming call never asks for a stream");

            client.chat().create("co_1", ChatMessageRequest.builder().message("m").dialogId(JsonNullable.ofNull())
                .turn(9007199254740993L).capabilities(Collections.emptyList()).build());
            body = TestSupport.json(server.request(1).text());
            assertTrue(body.has("dialog_id") && body.get("dialog_id").isNull(), "an explicit null is sent");
            assertTrue(server.request(1).text().contains("\"turn\":9007199254740993"), server.request(1).text());
            assertTrue(body.get("capabilities").isArray() && body.get("capabilities").size() == 0, "an empty array is sent");
        }
    }

    @Test
    void patchModelsExpressExplicitNull() {
        AutomationHookPatch absent = AutomationHookPatch.builder().build();
        assertEquals("{}", new String(Json.encode(absent), java.nio.charset.StandardCharsets.UTF_8));
        AutomationHookPatch cleared = AutomationHookPatch.builder().idPath(JsonNullable.ofNull()).signatureSecret("s").build();
        assertEquals("{\"id_path\":null,\"signature_secret\":\"s\"}",
            new String(Json.encode(cleared), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(cleared.idPath().isNull());
        assertTrue(absent.idPath().isUndefined());
        ChatDialogPatch patch = ChatDialogPatch.builder().title("Q4").build();
        assertEquals("{\"title\":\"Q4\"}", new String(Json.encode(patch), java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ValidationException.class, () -> ChatDialogPatch.builder().build(), "a required field is checked locally");
    }

    @Test
    void nonFiniteNumbersAreNeverSent() {
        Money money = Money.builder().amount(Double.NaN).amountTiyn(1L).formatted("x").build();
        ValidationException e = assertThrows(ValidationException.class, () -> Json.encode(money));
        assertNotNull(e.getCause());
        assertNull(Json.tryParse("NaN"));
    }
}
