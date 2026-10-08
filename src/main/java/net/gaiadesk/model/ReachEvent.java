package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * One online or offline transition in a desk's reach log.
 */
public final class ReachEvent extends ModelObject {
    private long at;
    private boolean online;
    private String reason = "";
    private String reasonText = "";
    private @Nullable String detail;
    private @Nullable String version;

    /** For JSON only. */
    ReachEvent() {}

    /** Unix seconds. */
    public long getAt() {
        return at;
    }

    /** It came online ({@code true}) or went offline. */
    public boolean isOnline() {
        return online;
    }

    /** Why ({@code registered}, {@code silent}, {@code closed}, ...). */
    public String getReason() {
        return reason;
    }

    /** Why, for a person. */
    public String getReasonText() {
        return reasonText;
    }

    /** More detail, when there is any. */
    public @Nullable String getDetail() {
        return detail;
    }

    /** Its GaiaDesk version, coming online. */
    public @Nullable String getVersion() {
        return version;
    }
}
