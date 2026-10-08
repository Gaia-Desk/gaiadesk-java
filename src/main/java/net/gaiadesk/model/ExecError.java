package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * What went wrong, as gaiadesk-cli reports it: the object inside {@code {"error": {...}}} and an exec result's {@code error}. {@code kind} is one of {@code usage}, {@code refused}, {@code unreachable}, {@code connection_lost}, {@code failed}, {@code protocol}; {@code reason} is the finer cause.
 */
public final class ExecError extends ModelObject {
    private String kind = "";
    private String message = "";
    private @Nullable String reason;
    private @Nullable String desk;

    /** For JSON only. */
    ExecError() {}

    /** One of the six kinds. */
    public String getKind() {
        return kind;
    }

    /** What happened, for a person. */
    public String getMessage() {
        return message;
    }

    /** The finer cause ({@code offline}, {@code timeout}, {@code admin_denied}, ...). */
    public @Nullable String getReason() {
        return reason;
    }

    /** The desk it concerned. */
    public @Nullable String getDesk() {
        return desk;
    }
}
