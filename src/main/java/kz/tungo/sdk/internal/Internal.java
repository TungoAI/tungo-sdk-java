package kz.tungo.sdk.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.tungo.sdk.errors.ValidationException;

/**
 * Small helpers of the generated models. Internal: not part of the public API.
 */
public final class Internal {
    private Internal() {
    }

    /**
     * Copies a list into an unmodifiable one; null elements are allowed.
     *
     * @param source the list, may be null
     * @param <T> element type
     * @return an unmodifiable copy, or null when the source is null
     */
    public static <T> List<T> list(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * Copies a map into an unmodifiable one that keeps the order; null values are allowed.
     *
     * @param source the map, may be null
     * @param <V> value type
     * @return an unmodifiable copy, or null when the source is null
     */
    public static <V> Map<String, V> map(Map<String, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Copies a required list; called by the constructor Jackson uses, so a response without the
     * list fails to decode instead of handing out null.
     *
     * @param source the list
     * @param name the JSON property name
     * @param <T> element type
     * @return an unmodifiable copy, never null
     * @throws IllegalArgumentException when the list is missing or null
     */
    public static <T> List<T> requiredList(List<T> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException("the required property \"" + name + "\" is missing or null");
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * Copies a required map; see {@link #requiredList}.
     *
     * @param source the map
     * @param name the JSON property name
     * @param <V> value type
     * @return an unmodifiable copy that keeps the order, never null
     * @throws IllegalArgumentException when the map is missing or null
     */
    public static <V> Map<String, V> requiredMap(Map<String, V> source, String name) {
        if (source == null) {
            throw new IllegalArgumentException("the required property \"" + name + "\" is missing or null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Checks that a required builder property is set.
     *
     * @param value the value
     * @param name the JSON property name
     * @param <T> value type
     * @return the value
     * @throws ValidationException when the value is null
     */
    public static <T> T required(T value, String name) {
        if (value == null) {
            throw new ValidationException("The required property \"" + name + "\" is not set");
        }
        return value;
    }

    /**
     * Converts a JSON tree to a generic type.
     *
     * @param json the tree
     * @param type the target type
     * @param <T> the target type
     * @return the value, or null when the tree does not match the type
     */
    public static <T> T convert(JsonNode json, TypeReference<T> type) {
        try {
            return Json.MAPPER.readValue(Json.MAPPER.treeAsTokens(json), type);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Tells whether a property of a JSON object holds a given scalar value.
     *
     * @param json the object
     * @param name the property name
     * @param value the expected value as text
     * @return true when the property is a scalar whose text form equals the value
     */
    public static boolean hasValue(JsonNode json, String name, String value) {
        JsonNode node = json.get(name);
        return node != null && node.isValueNode() && !node.isNull() && node.asText().equals(value);
    }
}
