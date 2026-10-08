package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * Who did it.
 */
public final class AuditActor extends ModelObject {
    private @Nullable String type;
    private @Nullable String id;

    /** For JSON only. */
    AuditActor() {}

    /** {@code user}, {@code api_key}, {@code agent_token}, ... */
    public @Nullable String getType() {
        return type;
    }

    /** Their id (an email, a key or token id). */
    public @Nullable String getId() {
        return id;
    }
}
