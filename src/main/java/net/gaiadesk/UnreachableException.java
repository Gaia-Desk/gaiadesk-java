package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** The desk or the API could not be reached: {@code offline}, {@code unknown_desk}, {@code network}, {@code timeout}, {@code no_wake_path}, {@code local_api_unavailable}, ... */
public class UnreachableException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public UnreachableException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public UnreachableException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
