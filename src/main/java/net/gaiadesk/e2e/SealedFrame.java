package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/** A sealed frame after the request: an event coming back, or a piece of input going up ({@code {"seq", "nonce", "ciphertext"}}). */
public final class SealedFrame {
    private final long seq;
    private final String nonce;
    private final String ciphertext;

    /** A frame's fields. */
    public SealedFrame(long seq, String nonce, String ciphertext) {
        this.seq = seq;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
    }

    /** Its place, from 0. */
    public long getSeq() { return seq; }

    /** The 24-byte nonce, base64url. */
    public String getNonce() { return nonce; }

    /** The ciphertext and its tag, base64url. */
    public String getCiphertext() { return ciphertext; }

    /** As JSON. */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.put("seq", seq);
        o.put("nonce", nonce);
        o.put("ciphertext", ciphertext);
        return o;
    }

    /** Read one from JSON; null when it is not one (a missing or mistyped field). */
    public static @Nullable SealedFrame fromJson(@Nullable JsonNode j) {
        if (j == null || !j.isObject() || !j.path("seq").isIntegralNumber() || !j.path("nonce").isTextual() || !j.path("ciphertext").isTextual()) return null;
        return new SealedFrame(j.get("seq").asLong(), j.get("nonce").asText(), j.get("ciphertext").asText());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof SealedFrame && toJson().equals(((SealedFrame) o).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
