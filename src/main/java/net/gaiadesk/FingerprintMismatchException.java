package net.gaiadesk;

/**
 * The LAN gateway's certificate did not match the pinned fingerprint: it is not the desk you pinned. Do not
 * proceed. Nothing of the request was sent (the check runs during the TLS handshake). Reason
 * {@code fingerprint_mismatch}.
 */
public class FingerprintMismatchException extends UnreachableException {
    private static final long serialVersionUID = 1L;

    private final String expected;
    private final String actual;

    /** The error for a certificate whose SHA-256 is {@code actual}, not the pinned {@code expected}. */
    public FingerprintMismatchException(String message, String expected, String actual, ErrorDetails details) {
        super(message, details);
        this.expected = expected;
        this.actual = actual;
    }

    /** The pinned fingerprint ({@code ab:cd:…}). */
    public String getExpected() {
        return expected;
    }

    /** The fingerprint the server presented ({@code ab:cd:…}), or empty when it presented none. */
    public String getActual() {
        return actual;
    }
}
