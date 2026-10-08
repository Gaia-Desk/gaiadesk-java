package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * One desk ({@code GET /desks/{id}}), with how it could be woken.
 */
public final class DeskDetail extends Device {
    private @Nullable WakeHints wake;

    /** For JSON only. */
    DeskDetail() {}

    /** How it could be woken now. */
    public @Nullable WakeHints getWake() {
        return wake;
    }
}
