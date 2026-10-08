package net.gaiadesk.internal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The SDK's one ObjectMapper: snake_case JSON to camelCase fields, unknown fields kept, nothing else. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {}

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode array() {
        return JsonNodeFactory.instance.arrayNode();
    }

    /** Parse JSON text (leading whitespace allowed); a RuntimeException when it is not JSON. */
    public static JsonNode parse(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n == null || n.isMissingNode()) throw new IllegalArgumentException("empty");
            return n;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON", e);
        }
    }

    /** Parse, or null when it is not JSON. */
    public static @Nullable JsonNode tryParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String write(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode tree(Object v) {
        return MAPPER.valueToTree(v);
    }

    /** A JSON value as a result type. */
    public static <T> T convert(JsonNode n, Class<T> type) {
        try {
            return MAPPER.treeToValue(n, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> List<T> convertList(JsonNode arr, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(convert(n, type));
        return out;
    }

    public static boolean isObj(@Nullable JsonNode n) {
        return n != null && n.isObject();
    }

    /** A text field, or null. */
    public static @Nullable String text(@Nullable JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
