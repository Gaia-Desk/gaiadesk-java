package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every failure the SDK reports. Unchecked: catch the class you can act on.
 *
 * <p>The class follows the error envelope's {@code kind} ({@link UsageException}, {@link RefusedException},
 * {@link UnreachableException}, {@link ConnectionLostException}, {@link OperationFailedException},
 * {@link ProtocolException}); {@link #getKind()} is the finest kind known (the envelope's {@code reason} when
 * it is one of the SDK's kinds, so {@code offline} stays {@code offline}); {@link #getReason()} is the finer
 * cause as the server said it. Branch on the class, then the kind, then the reason.
 *
 * <p>SDK kinds: {@code usage}, {@code offline}, {@code unknown_desk}, {@code not_online}, {@code refused},
 * {@code network}, {@code not_signed_in}, {@code timeout}, {@code connection_lost}, {@code local},
 * {@code failed}, {@code interrupted}, {@code protocol}, {@code unreachable} (and {@code cli_error} for
 * anything else).
 */
public class GaiaDeskException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ErrorDetails details;

    /** An error with these details. */
    public GaiaDeskException(String message, ErrorDetails details) {
        super(message);
        this.details = details;
    }

    /** An error with these details and its cause. */
    public GaiaDeskException(String message, ErrorDetails details, @Nullable Throwable cause) {
        super(message, cause);
        this.details = details;
    }

    /** The details this error was made with. */
    public ErrorDetails getDetails() {
        return details;
    }

    /** The SDK kind: {@code usage}, {@code offline}, {@code refused}, {@code network}, {@code timeout}, ... */
    public String getKind() {
        return details.kind;
    }

    /** The finer cause the server or desk gave ({@code rate_limited}, {@code e2e_required}, {@code desk_busy}, ...), or null. */
    public @Nullable String getReason() {
        return details.reason;
    }

    /** The desk the error concerned, when known; else null. */
    public @Nullable String getDesk() {
        return details.desk;
    }

    /**
     * The exit code {@code gaiadesk-cli} would have exited with: 254 refused, 1 a desk operation that failed,
     * 130 interrupted, 255 anything else; for a command that never ran, its exec exit. Null when none applies.
     */
    public @Nullable Integer getExitCode() {
        return details.exitCode;
    }

    /** What was being done when it failed, as {@code ["POST /desks/123456789/exec"]}. */
    public List<String> getArgv() {
        return details.argv;
    }

    /** The JSON that reported the error (the error envelope, or the result it came with), or null. */
    public @Nullable JsonNode getJson() {
        return details.json;
    }

    /** The failed request's id ({@code req_…}), to quote to support; else null. */
    public @Nullable String getRequestId() {
        return details.requestId;
    }

    /** The HTTP status of the failed request; else null. */
    public @Nullable Integer getStatus() {
        return details.status;
    }

    /** Seconds to wait before retrying (a 429's {@code Retry-After}); else null. */
    public @Nullable Double getRetryAfter() {
        return details.retryAfter;
    }
}
