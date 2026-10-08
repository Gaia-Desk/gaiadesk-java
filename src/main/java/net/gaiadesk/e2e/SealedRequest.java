package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/**
 * A sealed request: {@code {"v": 1, "pub", "nonce", "ciphertext"}}, base64url without padding. Sent as the
 * body {@code {"e2e": …}} of a POST, or as the {@code GaiaDesk-E2E} header ({@link E2eCrypto#requestHeader}).
 */
public final class SealedRequest {
    private final int v;
    private final String pub;
    private final String nonce;
    private final String ciphertext;

    /** A sealed request's fields. */
    public SealedRequest(int v, String pub, String nonce, String ciphertext) {
        this.v = v;
        this.pub = pub;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
    }

    /** The version, 1. */
    public int getV() { return v; }

    /** The caller's ephemeral X25519 public key. */
    public String getPub() { return pub; }

    /** The 24-byte nonce. */
    public String getNonce() { return nonce; }

    /** The ciphertext and its tag. */
    public String getCiphertext() { return ciphertext; }

    /** As JSON, in the protocol's field order. */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.put("v", v);
        o.put("pub", pub);
        o.put("nonce", nonce);
        o.put("ciphertext", ciphertext);
        return o;
    }

    /** Read one from JSON; null when it is not one. */
    public static @Nullable SealedRequest fromJson(@Nullable JsonNode j) {
        if (j == null || !j.isObject() || !j.path("pub").isTextual() || !j.path("nonce").isTextual() || !j.path("ciphertext").isTextual()) return null;
        return new SealedRequest(j.path("v").asInt(0), j.get("pub").asText(), j.get("nonce").asText(), j.get("ciphertext").asText());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof SealedRequest && toJson().equals(((SealedRequest) o).toJson());
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
