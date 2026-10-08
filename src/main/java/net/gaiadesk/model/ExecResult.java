package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a command ran ({@code exec --json}): its exit, output and duration. {@code exit} is what gaiadesk-cli exits with (the command's code, 124 timed out, 130 interrupted, 254 refused); {@code error} is null when the command ran and ended on its own.
 */
public final class ExecResult extends ModelObject {
    private int exit;
    private @Nullable Integer remoteCode;
    private String stdout = "";
    private String stderr = "";
    private long durationMs;
    private String desk = "";
    private @Nullable String route;
    private @Nullable String mode;
    private @Nullable String shell;
    private boolean timedOut;
    private @Nullable ExecError error;
    private List<String> notes = Collections.emptyList();
    private boolean truncated;

    /** For JSON only. */
    ExecResult() {}

    /** What gaiadesk-cli exits with: the command's own code, 124 timed out, 130 interrupted, 254 refused. */
    public int getExit() {
        return exit;
    }

    /** The command's own exit code; null when it never ran or was killed. */
    public @Nullable Integer getRemoteCode() {
        return remoteCode;
    }

    /** Its standard output (UTF-8 text). */
    public String getStdout() {
        return stdout;
    }

    /** Its standard error. */
    public String getStderr() {
        return stderr;
    }

    /** How long it ran. */
    public long getDurationMs() {
        return durationMs;
    }

    /** The desk it ran on. */
    public String getDesk() {
        return desk;
    }

    /** How the desk was reached. */
    public @Nullable String getRoute() {
        return route;
    }

    /** {@code pipes} or {@code pty}. */
    public @Nullable String getMode() {
        return mode;
    }

    /** The shell that ran it. */
    public @Nullable String getShell() {
        return shell;
    }

    /** It ran out of time ({@code timeout_secs}). */
    public boolean isTimedOut() {
        return timedOut;
    }

    /** Why it did not run, or did not end on its own; null otherwise. */
    public @Nullable ExecError getError() {
        return error;
    }

    /** Notes about the run. */
    public List<String> getNotes() {
        return notes;
    }

    /** The output was cut at the API's 8 MB. */
    public boolean isTruncated() {
        return truncated;
    }
}
