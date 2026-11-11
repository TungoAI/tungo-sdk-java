package kz.tungo.sdk.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kz.tungo.sdk.errors.DecodeException;
import kz.tungo.sdk.errors.ValidationException;

/**
 * The JSON codec of the SDK (Jackson). Internal: not part of the public API.
 */
public final class Json {
    /**
     * The shared mapper: unknown properties are ignored, trailing content is rejected, NaN and
     * infinite numbers are refused when writing. Scalars are not converted between JSON types
     * when reading: a fraction where an integer is expected ({@code 12.7}), a string where a
     * number or a boolean is expected ({@code "12"}), and a number or a boolean where a string is
     * expected are decode errors instead of silently changed values.
     */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .withCoercionConfig(LogicalType.Integer, config -> config
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Float, config -> config
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Boolean, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .addModule(finiteNumbers())
        .build();

    private Json() {
    }

    private static SimpleModule finiteNumbers() {
        SimpleModule module = new SimpleModule("tungo-finite-numbers");
        module.addSerializer(Double.class, new FiniteDouble());
        module.addSerializer(Double.TYPE, new FiniteDouble());
        module.addSerializer(Float.class, new FiniteFloat());
        module.addSerializer(Float.TYPE, new FiniteFloat());
        return module;
    }

    /**
     * Serializes a value to UTF-8 JSON.
     *
     * @param value the value, may be null
     * @return the JSON bytes
     * @throws ValidationException when the value cannot be represented as JSON
     */
    public static byte[] encode(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new ValidationException("The value cannot be serialized to JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Converts a value to a JSON tree.
     *
     * @param value the value, may be null
     * @return the tree
     * @throws ValidationException when the value cannot be represented as JSON
     */
    public static JsonNode toTree(Object value) {
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("The value cannot be serialized to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes a value to a JSON string for diagnostics; never throws.
     *
     * @param value the value
     * @return the JSON text, or a placeholder when serialization fails
     */
    public static String debugString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException e) {
            return "<unserializable>";
        }
    }

    /**
     * Serializes a value for {@code toString} with some of its properties hidden: a credential
     * (an API key, a signing secret, a hook token) must not reach logs through a printed model.
     *
     * @param value the value
     * @param redacted names of the top-level properties whose values are replaced
     * @return the JSON text, or a placeholder when serialization fails
     */
    public static String debugString(Object value, String... redacted) {
        try {
            JsonNode tree = MAPPER.valueToTree(value);
            if (tree instanceof ObjectNode) {
                ObjectNode object = (ObjectNode) tree;
                for (String name : redacted) {
                    JsonNode current = object.get(name);
                    if (current != null && !current.isNull()) {
                        object.put(name, "[REDACTED]");
                    }
                }
            }
            return MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException | RuntimeException e) {
            return "<unserializable>";
        }
    }

    /**
     * Builds a Jackson type for a class.
     *
     * @param type the class
     * @return the Jackson type
     */
    public static JavaType type(Class<?> type) {
        return MAPPER.constructType(type);
    }

    /**
     * Builds a Jackson type for a generic type.
     *
     * @param type the type reference
     * @return the Jackson type
     */
    public static JavaType type(TypeReference<?> type) {
        return MAPPER.constructType(type);
    }

    /**
     * Decodes a UTF-8 JSON document.
     *
     * @param bytes the document
     * @param type the expected type
     * @param <T> the expected type
     * @return the decoded value
     * @throws IOException when the document is not valid JSON or does not match the type
     */
    public static <T> T decode(byte[] bytes, JavaType type) throws IOException {
        return MAPPER.readValue(bytes, type);
    }

    /**
     * Decodes a JSON text, reporting failures as {@link DecodeException}.
     *
     * @param text the JSON text
     * @param type the expected class
     * @param what a short description of the text for the error message
     * @param <T> the expected type
     * @return the decoded value
     */
    public static <T> T decodeText(String text, Class<T> type, String what) {
        try {
            T value = MAPPER.readValue(text.getBytes(StandardCharsets.UTF_8), type);
            if (value == null) {
                throw new DecodeException("Cannot decode " + what + ": the JSON value is null", null, 0, null, text);
            }
            return value;
        } catch (IOException e) {
            throw new DecodeException("Cannot decode " + what + ": " + shortMessage(e), e, 0, null, text);
        }
    }

    /**
     * Parses a JSON text to a tree.
     *
     * @param text the text, may be null
     * @return the tree, or null when the text is not valid JSON
     */
    public static JsonNode tryParse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Converts a tree to a typed value.
     *
     * @param node the tree, may be null
     * @param type the target class
     * @param <T> the target type
     * @return the value, or null when the tree is null or does not match the type
     */
    public static <T> T tryConvert(JsonNode node, Class<T> type) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Shortens a Jackson error message to its first line, without source location noise.
     *
     * @param e the failure
     * @return a one-line description
     */
    public static String shortMessage(IOException e) {
        String message = e instanceof JsonProcessingException
            ? ((JsonProcessingException) e).getOriginalMessage()
            : e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    private static final class FiniteDouble extends StdSerializer<Double> {
        private static final long serialVersionUID = 1L;

        FiniteDouble() {
            super(Double.class);
        }

        @Override
        public void serialize(Double value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value.isNaN() || value.isInfinite()) {
                throw new com.fasterxml.jackson.databind.JsonMappingException(gen, "NaN and infinite numbers cannot be sent as JSON");
            }
            gen.writeNumber(value);
        }
    }

    private static final class FiniteFloat extends StdSerializer<Float> {
        private static final long serialVersionUID = 1L;

        FiniteFloat() {
            super(Float.class);
        }

        @Override
        public void serialize(Float value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value.isNaN() || value.isInfinite()) {
                throw new com.fasterxml.jackson.databind.JsonMappingException(gen, "NaN and infinite numbers cannot be sent as JSON");
            }
            gen.writeNumber(value);
        }
    }
}
