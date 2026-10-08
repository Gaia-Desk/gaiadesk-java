package net.gaiadesk;

/** A stream read to its end ({@link ExecStream#collect()}): its output, and how it ended. */
public final class StreamResult {
    private final String stdout;
    private final String stderr;
    private final Exit exit;

    StreamResult(String stdout, String stderr, Exit exit) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exit = exit;
    }

    /** Everything on standard output (a job's log). */
    public String getStdout() {
        return stdout;
    }

    /** Everything on standard error. */
    public String getStderr() {
        return stderr;
    }

    /** How it ended. */
    public Exit getExit() {
        return exit;
    }

    @Override
    public String toString() {
        return "StreamResult{exit=" + exit + "}";
    }
}
