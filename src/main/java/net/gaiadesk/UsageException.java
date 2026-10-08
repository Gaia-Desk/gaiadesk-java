package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** Bad arguments or a malformed request (kind {@code usage}): fix the call. Also an operation the transport does not serve. */
public class UsageException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public UsageException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public UsageException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
