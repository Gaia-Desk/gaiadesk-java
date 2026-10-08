package net.gaiadesk.e2e;

/** A sealed message did not open: {@code e2e_malformed}, {@code e2e_decrypt_failed} or {@code e2e_weak_key}. */
public final class E2eOpenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String reason;

    /** The error for a message that did not open, for this reason. */
    public E2eOpenException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** {@code e2e_malformed}, {@code e2e_decrypt_failed} or {@code e2e_weak_key}. */
    public String getReason() {
        return reason;
    }
}
