package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** An answer that is not what the contract documents (kind {@code protocol}): not JSON, no error envelope, a desk too old for the request ({@code desk_too_old}), or an end-to-end encrypted answer that did not open ({@code e2e_decrypt_failed}, {@code e2e_malformed}, {@code e2e_unsealed_answer}). */
public class ProtocolException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public ProtocolException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public ProtocolException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
