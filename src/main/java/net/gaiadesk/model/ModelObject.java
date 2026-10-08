package net.gaiadesk.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/**
 * A result object: the JSON the API (and {@code gaiadesk-cli --json}) answers, with its own field names in
 * camelCase getters. Fields this SDK version does not know are kept in {@link #getOther()}; {@link #toJson()}
 * gives the whole object back as JSON. Two results are equal when their JSON is.
 */
public abstract class ModelObject {
    private final Map<String, JsonNode> other = new LinkedHashMap<>();

    ModelObject() {}

    @JsonAnySetter
    void putOther(String name, JsonNode value) {
        other.put(name, value);
    }

    @JsonAnyGetter
    Map<String, JsonNode> anyOther() {
        return other;
    }

    /** Fields of the JSON this SDK version does not model, by their JSON names. */
    public Map<String, JsonNode> getOther() {
        return Collections.unmodifiableMap(other);
    }

    /** The object as JSON, with the API's own (snake_case) field names. */
    public JsonNode toJson() {
        return Json.tree(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o != null && o.getClass() == getClass() && toJson().equals(((ModelObject) o).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    /** The object as compact JSON. */
    @Override
    public String toString() {
        return toJson().toString();
    }
}
