package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** The desk went away during the operation (kind {@code connection_lost}), or a download ended before the desk said it was complete. */
public class ConnectionLostException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public ConnectionLostException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public ConnectionLostException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
