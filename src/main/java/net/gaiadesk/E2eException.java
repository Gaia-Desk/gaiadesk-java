package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/**
 * End-to-end encryption refused to send an operation in the clear, so nothing was sent to the desk:
 * reason {@code e2e_unavailable} ({@link E2eMode#REQUIRE}, or a desk that requires it, and no key for the desk
 * even after waking it), or {@code e2e_key_mismatch} (the server listed a key other than the pinned one).
 */
public class E2eException extends RefusedException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public E2eException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public E2eException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
