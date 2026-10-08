package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The end of a run without its output: a stream's last {@code exit} event.
 */
public final class ExecExit extends ModelObject {
    private int exit;
    private @Nullable Integer remoteCode;
    private long durationMs;
    private String desk = "";
    private @Nullable String route;
    private @Nullable String mode;
    private @Nullable String shell;
    private boolean timedOut;
    private @Nullable ExecError error;
    private List<String> notes = Collections.emptyList();

    /** For JSON only. */
    ExecExit() {}

    /** As {@link ExecResult#getExit()}. */
    public int getExit() {
        return exit;
    }

    /** The command's own exit code; null when it never ran. */
    public @Nullable Integer getRemoteCode() {
        return remoteCode;
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

    /** It ran out of time. */
    public boolean isTimedOut() {
        return timedOut;
    }

    /** Why it did not run, or did not end on its own. */
    public @Nullable ExecError getError() {
        return error;
    }

    /** Notes about the run. */
    public List<String> getNotes() {
        return notes;
    }
}
