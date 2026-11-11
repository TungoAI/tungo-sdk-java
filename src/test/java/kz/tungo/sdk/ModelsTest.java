package kz.tungo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.internal.Json;
import kz.tungo.sdk.models.AutomationFilterConditionValue;
import kz.tungo.sdk.models.AutomationRunDetail;
import kz.tungo.sdk.models.AutomationRunDetailState;
import kz.tungo.sdk.models.ChatEvent;
import kz.tungo.sdk.models.ChatEventDone;
import kz.tungo.sdk.models.ChatEventText;
import kz.tungo.sdk.models.ChatMessageRequest;
import kz.tungo.sdk.models.Company;
import kz.tungo.sdk.models.TaxFormListItemRequired;
import kz.tungo.sdk.models.WebhookEvent;
import org.junit.jupiter.api.Test;

class ModelsTest {
    private static <T> T decode(String json, Class<T> type) {
        return Json.decodeText(json, type, "test");
    }

    private static String encode(Object value) {
        return new String(Json.encode(value), StandardCharsets.UTF_8);
    }

    @Test
    void unknownFieldsAreKeptAndNeverFailDecoding() {
        Company company = decode("{\"id\":\"co_1\",\"permissions\":[],\"name\":\"Ромашка\",\"hologram\":{\"a\":[1,2]},\"new_flag\":true}", Company.class);
        assertEquals("co_1", company.id());
        assertEquals(2, company.additionalProperties().size());
        assertTrue(company.additionalProperties().get("new_flag").asBoolean());
        JsonNode again = TestSupport.json(encode(company));
        assertEquals(2, again.get("hologram").get("a").get(1).asInt());
        assertThrows(UnsupportedOperationException.class, () -> company.additionalProperties().clear());
    }

    @Test
    void modelsAreImmutableValuesWithBuilders() {
        ChatMessageRequest a = ChatMessageRequest.builder().message("m").capabilities(List.of("x")).build();
        ChatMessageRequest b = a.toBuilder().build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.toBuilder().message("other").build());
        assertTrue(a.toString().contains("\"message\":\"m\""), a.toString());
        assertThrows(UnsupportedOperationException.class, () -> a.capabilities().orElseThrow().add("y"));
        assertEquals("m", a.message().orElseThrow());
        assertTrue(a.dialogId().isUndefined());
    }

    @Test
    void unknownEnumValuesArePreserved() {
        AutomationRunDetail run = decode("{\"id\":\"run_1\",\"state\":\"teleporting\",\"proposals\":[],\"steps\":[],\"tools\":[]}", AutomationRunDetail.class);
        assertEquals("teleporting", run.state().value());
        assertFalse(run.state().isKnown());
        assertEquals(AutomationRunDetailState.Known.UNKNOWN_VALUE, run.state().known());
        assertTrue(encode(run).contains("\"state\":\"teleporting\""));

        AutomationRunDetail done = decode("{\"id\":\"run_1\",\"state\":\"done\",\"proposals\":[],\"steps\":[],\"tools\":[]}", AutomationRunDetail.class);
        assertEquals(AutomationRunDetailState.DONE, done.state());
        assertEquals(AutomationRunDetailState.Known.DONE, done.state().known());
        assertTrue(AutomationRunDetailState.values().contains(AutomationRunDetailState.DONE));
    }

    @Test
    void int64SurvivesARoundTrip() {
        AutomationRunDetail run = decode("{\"id\":\"r\",\"state\":\"done\",\"proposals\":[],\"steps\":[],\"tools\":[],\"cost_tiyn\":9007199254740993}", AutomationRunDetail.class);
        assertEquals(9007199254740993L, run.costTiyn().orElseThrow().longValue());
        assertTrue(encode(run).contains("9007199254740993"));
    }

    @Test
    void discriminatedUnionsDecodeToTypedVariants() {
        ChatEvent text = decode("{\"type\":\"text\",\"delta\":\"Привет\",\"step\":1}", ChatEvent.class);
        assertTrue(text instanceof ChatEventText);
        assertEquals("Привет", ((ChatEventText) text).delta());
        ChatEvent done = decode("{\"type\":\"done\",\"stop_reason\":\"end_turn\",\"cost_tiyn\":185}", ChatEvent.class);
        assertTrue(done instanceof ChatEventDone);
        assertEquals(185L, ((ChatEventDone) done).costTiyn().orElseThrow().longValue());
        assertEquals("text", text.type());
        assertTrue(encode(text).contains("\"type\":\"text\""), encode(text));
    }

    @Test
    void unknownUnionVariantsAreKeptAsUnknown() {
        ChatEvent event = decode("{\"type\":\"hologram\",\"payload\":{\"x\":1}}", ChatEvent.class);
        assertTrue(event instanceof ChatEvent.Unknown);
        assertEquals("hologram", event.type());
        assertEquals(1, ((ChatEvent.Unknown) event).json().get("payload").get("x").asInt());
        assertEquals("{\"type\":\"hologram\",\"payload\":{\"x\":1}}", encode(event));

        ChatEvent missing = decode("{\"delta\":\"x\"}", ChatEvent.class);
        assertTrue(missing instanceof ChatEvent.Unknown);
        assertThrows(DecodeException.class, () -> decode("[1,2]", ChatEvent.class));
    }

    @Test
    void unionVisitorsCoverEveryVariant() {
        ChatEvent text = decode("{\"type\":\"text\",\"delta\":\"x\"}", ChatEvent.class);
        String seen = text.accept(new ChatEvent.Visitor<String>() {
            @Override
            public String visitText(ChatEventText event) {
                return "text:" + event.delta();
            }

            @Override
            public String visitDefault(ChatEvent event) {
                return "other";
            }
        });
        assertEquals("text:x", seen);
    }

    @Test
    void webhookEventsAreATaggedUnion() {
        WebhookEvent event = decode("{\"id\":\"evt_1\",\"type\":\"brand.new\",\"data\":{}}", WebhookEvent.class);
        assertTrue(event instanceof WebhookEvent.Unknown);
        assertEquals("brand.new", event.type());
    }

    @Test
    void primitiveUnionsHoldEveryAllowedShape() {
        assertTrue(decode("true", TaxFormListItemRequired.class).asBoolean().orElseThrow());
        assertEquals("unknown", decode("\"unknown\"", TaxFormListItemRequired.class).asString().orElseThrow());
        assertFalse(decode("\"unknown\"", TaxFormListItemRequired.class).isBoolean());

        assertEquals("a", decode("\"a\"", AutomationFilterConditionValue.class).asString().orElseThrow());
        assertEquals(1.5, decode("1.5", AutomationFilterConditionValue.class).asNumber().orElseThrow(), 0.0);
        assertTrue(decode("false", AutomationFilterConditionValue.class).isBoolean());
        AutomationFilterConditionValue list = decode("[\"a\",2]", AutomationFilterConditionValue.class);
        assertTrue(list.isList());
        assertEquals(2, list.asList().orElseThrow().size());
        assertEquals("[\"a\",2]", encode(list));
        assertEquals("\"x\"", encode(AutomationFilterConditionValue.ofString("x")));
        assertEquals("true", encode(AutomationFilterConditionValue.ofBoolean(true)));

        AutomationFilterConditionValue future = decode("{\"new\":\"shape\"}", AutomationFilterConditionValue.class);
        assertFalse(future.isString() || future.isNumber() || future.isBoolean() || future.isList());
        assertEquals("shape", future.json().get("new").asText());
    }

    @Test
    void jsonNullableDistinguishesAbsentNullAndValue() {
        ChatMessageRequest absent = decode("{\"message\":\"m\"}", ChatMessageRequest.class);
        assertTrue(absent.dialogId().isUndefined());
        ChatMessageRequest nulled = decode("{\"message\":\"m\",\"dialog_id\":null}", ChatMessageRequest.class);
        assertTrue(nulled.dialogId().isNull());
        ChatMessageRequest set = decode("{\"message\":\"m\",\"dialog_id\":\"d1\"}", ChatMessageRequest.class);
        assertEquals("d1", set.dialogId().get());
        assertEquals("{\"message\":\"m\"}", encode(absent));
        assertEquals("{\"dialog_id\":null,\"message\":\"m\"}", encode(nulled));
        assertEquals(JsonNullable.of("d1"), set.dialogId());
        assertEquals(JsonNullable.undefined(), absent.dialogId());
        assertNotEquals(JsonNullable.ofNull(), JsonNullable.undefined());
    }

    @Test
    void toStringHidesCredentials() {
        kz.tungo.sdk.models.WebhookEndpointCreated created =
            decode("{\"id\":\"ep_1\",\"secret\":\"whsec_TOPSECRET\"}", kz.tungo.sdk.models.WebhookEndpointCreated.class);
        String text = created.toString();
        assertFalse(text.contains("TOPSECRET"), text);
        assertTrue(text.contains("[REDACTED]"), text);
        assertTrue(text.contains("ep_1"), text);
        // The value itself stays readable, and serialization still carries it.
        assertTrue(String.valueOf(created.secret()).contains("whsec_TOPSECRET"));
        assertTrue(encode(created).contains("whsec_TOPSECRET"));

        kz.tungo.sdk.models.ApiKeyRolled rolled = decode("{\"key\":\"tg_TOPSECRET\",\"company_ids\":[],\"permissions\":[]}", kz.tungo.sdk.models.ApiKeyRolled.class);
        assertFalse(rolled.toString().contains("TOPSECRET"), rolled.toString());
        assertTrue(String.valueOf(rolled.key()).contains("tg_TOPSECRET"));

        kz.tungo.sdk.models.AutomationHookWithToken hook = decode(
            "{\"id\":\"hk_1\",\"token\":\"th_TOPSECRET\",\"url\":\"https://api.tungo.kz/hooks/th_TOPSECRET\"}",
            kz.tungo.sdk.models.AutomationHookWithToken.class);
        assertFalse(hook.toString().contains("TOPSECRET"), hook.toString());
    }
}
