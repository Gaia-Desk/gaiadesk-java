package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * What it was done to.
 */
public final class AuditTarget extends ModelObject {
    private @Nullable String type;
    private @Nullable String id;
    private @Nullable String name;

    /** For JSON only. */
    AuditTarget() {}

    /** {@code desk}, ... */
    public @Nullable String getType() {
        return type;
    }

    /** Its id. */
    public @Nullable String getId() {
        return id;
    }

    /** Its name. */
    public @Nullable String getName() {
        return name;
    }
}
