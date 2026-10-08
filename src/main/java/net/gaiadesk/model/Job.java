package net.gaiadesk.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A background job ({@code run --json}, {@code ps --json}, {@code kill --json}).
 */
public final class Job extends ModelObject {
    private String name = "";
    private String command = "";
    private String state = "";
    private @Nullable Long pid;
    private @Nullable Integer exitCode;
    private long startedAtMs;
    private @Nullable Long endedAtMs;
    private long logBytes;
    private @Nullable String by;
    private @Nullable JobLimits limits;
    private @Nullable List<String> enforcement;
    private @Nullable String reason;

    /** For JSON only. */
    Job() {}

    /** Its name. */
    public String getName() {
        return name;
    }

    /** Its command line. */
    public String getCommand() {
        return command;
    }

    /** {@code running}, {@code exited}, {@code killed}, {@code lost}, ... */
    public String getState() {
        return state;
    }

    /** Its process id while running. */
    public @Nullable Long getPid() {
        return pid;
    }

    /** How it exited (a result, not an error). */
    public @Nullable Integer getExitCode() {
        return exitCode;
    }

    /** Unix milliseconds. */
    public long getStartedAtMs() {
        return startedAtMs;
    }

    /** Unix milliseconds. */
    public @Nullable Long getEndedAtMs() {
        return endedAtMs;
    }

    /** Output kept. */
    public long getLogBytes() {
        return logBytes;
    }

    /** Who started it. */
    public @Nullable String getBy() {
        return by;
    }

    /** Its limits. */
    public @Nullable JobLimits getLimits() {
        return limits;
    }

    /** How the limits are enforced. */
    public @Nullable List<String> getEnforcement() {
        return enforcement;
    }

    /** Why it ended, when it did not exit. */
    public @Nullable String getReason() {
        return reason;
    }
}
