package kz.tungo.sdk;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * A field value with three states: undefined (the property is absent from the JSON), explicit
 * null, or a value. It is used for optional fields that also accept null, where "leave unchanged"
 * and "clear" are different requests (PATCH semantics).
 *
 * @param <T> type of the value
 */
@JsonSerialize(using = JsonNullable.Serializer.class)
@JsonDeserialize(using = JsonNullable.Deserializer.class)
public final class JsonNullable<T> {
    private static final JsonNullable<?> UNDEFINED = new JsonNullable<>(null, false);
    private static final JsonNullable<?> NULL = new JsonNullable<>(null, true);

    private final T value;
    private final boolean defined;

    private JsonNullable(T value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    /**
     * Returns the undefined state: the property is not sent.
     *
     * @param <T> type of the value
     * @return the undefined instance
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonNullable<T> undefined() {
        return (JsonNullable<T>) UNDEFINED;
    }

    /**
     * Returns the explicit null state: the property is sent as JSON {@code null}.
     *
     * @param <T> type of the value
     * @return the null instance
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonNullable<T> ofNull() {
        return (JsonNullable<T>) NULL;
    }

    /**
     * Wraps a value. A null argument gives the explicit null state.
     *
     * @param value the value, or null for an explicit JSON null
     * @param <T> type of the value
     * @return the wrapped value
     */
    public static <T> JsonNullable<T> of(T value) {
        return value == null ? ofNull() : new JsonNullable<>(value, true);
    }

    /**
     * Tells whether the property is absent.
     *
     * @return true in the undefined state
     */
    public boolean isUndefined() {
        return !defined;
    }

    /**
     * Tells whether the property is an explicit null.
     *
     * @return true in the null state
     */
    public boolean isNull() {
        return defined && value == null;
    }

    /**
     * Tells whether the property holds a value.
     *
     * @return true when a non-null value is present
     */
    public boolean isPresent() {
        return value != null;
    }

    /**
     * Returns the value.
     *
     * @return the non-null value
     * @throws NoSuchElementException when the state is undefined or null
     */
    public T get() {
        if (value == null) {
            throw new NoSuchElementException(defined ? "The value is null" : "The value is undefined");
        }
        return value;
    }

    /**
     * Returns the value or a fallback.
     *
     * @param other the fallback for the undefined and null states
     * @return the value, or {@code other}
     */
    public T orElse(T other) {
        return value != null ? value : other;
    }

    /**
     * Converts to an {@link Optional}, merging the undefined and null states.
     *
     * @return the value, or empty
     */
    public Optional<T> toOptional() {
        return Optional.ofNullable(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonNullable)) {
            return false;
        }
        JsonNullable<?> other = (JsonNullable<?>) o;
        return defined == other.defined && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defined, value);
    }

    @Override
    public String toString() {
        return defined ? "JsonNullable[" + value + "]" : "JsonNullable.undefined";
    }

    /** Jackson serializer: undefined is "empty" (skipped by the models), null is written as null. */
    static final class Serializer extends StdSerializer<JsonNullable<?>> {
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(JsonNullable.class, false);
        }

        @Override
        public boolean isEmpty(SerializerProvider provider, JsonNullable<?> value) {
            return value == null || value.isUndefined();
        }

        @Override
        public void serialize(JsonNullable<?> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value.value == null) {
                gen.writeNull();
            } else {
                provider.defaultSerializeValue(value.value, gen);
            }
        }
    }

    /** Jackson deserializer: absent gives undefined, null gives the null state. */
    static final class Deserializer extends StdDeserializer<JsonNullable<?>> implements ContextualDeserializer {
        private static final long serialVersionUID = 1L;

        private final JavaType valueType;
        private final transient JsonDeserializer<?> valueDeserializer;

        Deserializer() {
            this(null, null);
        }

        private Deserializer(JavaType valueType, JsonDeserializer<?> valueDeserializer) {
            super(JsonNullable.class);
            this.valueType = valueType;
            this.valueDeserializer = valueDeserializer;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
            JavaType wrapper = property != null ? property.getType() : ctxt.getContextualType();
            JavaType inner = wrapper != null && wrapper.containedTypeCount() > 0
                ? wrapper.containedType(0)
                : ctxt.constructType(Object.class);
            return new Deserializer(inner, ctxt.findContextualValueDeserializer(inner, property));
        }

        @Override
        public JsonNullable<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonDeserializer<?> deser = valueDeserializer != null
                ? valueDeserializer
                : ctxt.findRootValueDeserializer(ctxt.constructType(Object.class));
            return JsonNullable.of(deser.deserialize(p, ctxt));
        }

        @Override
        public JsonNullable<?> getNullValue(DeserializationContext ctxt) {
            return JsonNullable.ofNull();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return JsonNullable.undefined();
        }

        @Override
        public Object getEmptyValue(DeserializationContext ctxt) {
            return JsonNullable.undefined();
        }
    }
}
