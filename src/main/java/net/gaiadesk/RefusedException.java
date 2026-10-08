package net.gaiadesk;

import org.jspecify.annotations.Nullable;

/** The desk or the API said no (kind {@code refused}): a missing scope or desk token, an expired or revoked token, a rate limit ({@code rate_limited}, with {@link #getRetryAfter()}), {@code e2e_required}, or an administrator refusal ({@code admin_scope_missing}, {@code admin_not_enabled}, {@code admin_denied}, {@code admin_unavailable}). */
public class RefusedException extends GaiaDeskException {
    private static final long serialVersionUID = 1L;

    /** An error with these details. */
    public RefusedException(String message, ErrorDetails details) {
        super(message, details);
    }

    /** An error with these details and its cause. */
    public RefusedException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, details, cause);
    }
}
