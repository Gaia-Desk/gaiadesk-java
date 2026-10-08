package net.gaiadesk;

import net.gaiadesk.model.ExecResult;

/** {@code exec} with {@link ExecOptions#check(boolean) check}: the command exited non-zero, or timed out. */
public class CommandException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    private final transient ExecResult result;

    /** The error for a command that ran and did not succeed. */
    public CommandException(String message, ExecResult result, ErrorDetails details) {
        super(message, details);
        this.result = result;
    }

    /** How it ran: its exit, output and duration. */
    public ExecResult getResult() {
        return result;
    }
}
