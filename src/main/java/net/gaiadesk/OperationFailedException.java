package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** A desk operation ran and did not succeed (kind {@code failed}, exit 1): a file failed to copy, no job by that name, ... */
public class OperationFailedException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public OperationFailedException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public OperationFailedException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
