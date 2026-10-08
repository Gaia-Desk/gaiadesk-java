package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * A job's resource limits.
 */
public final class JobLimits extends ModelObject {
    private @Nullable String priority;
    private @Nullable Integer cpuPercent;
    private @Nullable Long memMb;
    private @Nullable Boolean keepAwake;

    /** For JSON only. */
    JobLimits() {}

    /** {@code low}, {@code normal}, {@code high}. */
    public @Nullable String getPriority() {
        return priority;
    }

    /** Share of the whole machine, 1-100. */
    public @Nullable Integer getCpuPercent() {
        return cpuPercent;
    }

    /** Memory, in megabytes. */
    public @Nullable Long getMemMb() {
        return memMb;
    }

    /** Keep the desk awake while it runs. */
    public @Nullable Boolean getKeepAwake() {
        return keepAwake;
    }
}
