package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * The last time a desk was reached.
 */
public final class ReachSuccess extends ModelObject {
    private long at;
    private String route = "";
    private @Nullable Long connectMs;
    private @Nullable Long rttMs;

    /** For JSON only. */
    ReachSuccess() {}

    /** Unix milliseconds. */
    public long getAt() {
        return at;
    }

    /** How. */
    public String getRoute() {
        return route;
    }

    /** How long connecting took. */
    public @Nullable Long getConnectMs() {
        return connectMs;
    }

    /** The round trip. */
    public @Nullable Long getRttMs() {
        return rttMs;
    }
}
