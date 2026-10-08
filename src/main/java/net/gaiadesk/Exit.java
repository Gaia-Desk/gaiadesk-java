package net.gaiadesk;

import net.gaiadesk.model.ExecError;
import net.gaiadesk.model.ExecExit;
import org.jspecify.annotations.Nullable;

/**
 * How a stream ended: its exit code (the command's, or 254 refused, 255 lost, 130 killed), the last thing said
 * about it, and, for a command, its end without the output ({@link #getResult()}) and why it did not end on
 * its own ({@link #getError()}).
 */
public final class Exit {
    private final @Nullable Integer exitCode;
    private final String stderrTail;
    private final @Nullable ExecExit result;
    private final @Nullable ExecError error;

    Exit(@Nullable Integer exitCode, String stderrTail, @Nullable ExecExit result, @Nullable ExecError error) {
        this.exitCode = exitCode;
        this.stderrTail = stderrTail;
        this.result = result;
        this.error = error;
    }

    /** The exit code: the command's own, 254 refused, 255 unreachable or lost, 130 killed; a job log's end is 0. */
    public @Nullable Integer getExitCode() {
        return exitCode;
    }

    /** Always null over the HTTP transports (no signals); kept for parity with the other SDKs' Exit. */
    public @Nullable String getSignal() {
        return null;
    }

    /** The last thing said about how it ended (an error's message, {@code job build exited (exit 0)}, ...). */
    public String getStderrTail() {
        return stderrTail;
    }

    /** A command's end without its output ({@code exit}, {@code remote_code}, {@code duration_ms}, ...); null for a job's log or a failure. */
    public @Nullable ExecExit getResult() {
        return result;
    }

    /** Why it did not end on its own (refused, lost, unreachable, ...); null when it did. */
    public @Nullable ExecError getError() {
        return error;
    }

    @Override
    public String toString() {
        return "Exit{exitCode=" + exitCode + ", stderrTail=" + stderrTail + (error != null ? ", error=" + error : "") + "}";
    }
}
