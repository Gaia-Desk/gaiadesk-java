package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST /desks/{id}/wake}: what was rung and whether the desk came online.
 */
public final class WakeResult extends ModelObject {
    private String deskId = "";
    private boolean online;
    private boolean woke;
    private boolean alreadyOnline;
    private @Nullable WakeRang rang;
    private long waitedMs;

    /** For JSON only. */
    WakeResult() {}

    /** The desk. */
    public String getDeskId() {
        return deskId;
    }

    /** Online now. */
    public boolean isOnline() {
        return online;
    }

    /** It came online after this ring, within {@code wait_s}. */
    public boolean isWoke() {
        return woke;
    }

    /** It was online already (not rung). */
    public boolean isAlreadyOnline() {
        return alreadyOnline;
    }

    /** What was rung. */
    public @Nullable WakeRang getRang() {
        return rang;
    }

    /** How long the call waited. */
    public long getWaitedMs() {
        return waitedMs;
    }
}
