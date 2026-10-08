package net.gaiadesk.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An agent token as the desk describes it (never its secret).
 */
public final class TokenInfo extends ModelObject {
    private String label = "";
    private String id = "";
    private long issuedAtMs;
    private long expiresAtMs;
    private @Nullable Long lastUsedMs;
    private @Nullable String cwd;
    private boolean lowPriv;
    private boolean revoked;
    private @Nullable List<String> scopes;

    /** For JSON only. */
    TokenInfo() {}

    /** Its name. */
    public String getLabel() {
        return label;
    }

    /** Its id. */
    public String getId() {
        return id;
    }

    /** Unix milliseconds. */
    public long getIssuedAtMs() {
        return issuedAtMs;
    }

    /** Unix milliseconds. */
    public long getExpiresAtMs() {
        return expiresAtMs;
    }

    /** Unix milliseconds it was last used. */
    public @Nullable Long getLastUsedMs() {
        return lastUsedMs;
    }

    /** The folder it is confined to. */
    public @Nullable String getCwd() {
        return cwd;
    }

    /** It runs as the desk's low-privilege agent user. */
    public boolean isLowPriv() {
        return lowPriv;
    }

    /** It was revoked. */
    public boolean isRevoked() {
        return revoked;
    }

    /** What it may do: {@code screen}, {@code exec}, {@code shell}, {@code cp}, {@code forward}, {@code jobs} ({@code admin} on a token minted with {@code gaiadesk-cli}). */
    public @Nullable List<String> getScopes() {
        return scopes;
    }
}
