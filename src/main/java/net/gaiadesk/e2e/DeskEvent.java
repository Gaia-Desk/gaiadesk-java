package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/**
 * What a sealed event opens to, the desk's own event: {@code {"event": "stdout"|"stderr", "data": <base64>}},
 * {@code {"event": "exit", "result": …}} or {@code {"event": "error", "kind", "message", "reason"?}}.
 */
public final class DeskEvent {
    private final ObjectNode json;

    DeskEvent(ObjectNode json) {
        this.json = json;
    }

    /** {@code stdout}, {@code stderr}, {@code exit} or {@code error}. */
    public String getEvent() { return json.path("event").asText(); }

    /** {@code stdout}/{@code stderr}: the bytes, base64. */
    public String getData() { return json.path("data").asText(""); }

    /** {@code exit}: the operation's result (the plaintext call's answer). */
    public JsonNode getResult() { return json.path("result"); }

    /** {@code error}: its kind. */
    public String getKind() { return json.path("kind").asText(""); }

    /** {@code error}: its message. */
    public String getMessage() { return json.path("message").asText(""); }

    /** {@code error}: its reason, or null. */
    public @Nullable String getReason() {
        JsonNode r = json.get("reason");
        return r != null && r.isTextual() ? r.asText() : null;
    }

    /** The event as JSON. */
    public ObjectNode toJson() { return json.deepCopy(); }

    /** An event from its JSON. */
    public static DeskEvent of(ObjectNode json) {
        return new DeskEvent(json.deepCopy());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof DeskEvent && json.equals(((DeskEvent) o).json);
    }

    @Override
    public int hashCode() { return json.hashCode(); }

    @Override
    public String toString() { return Json.write(json); }
}
