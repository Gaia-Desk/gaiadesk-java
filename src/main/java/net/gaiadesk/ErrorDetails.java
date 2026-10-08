package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What an error carries besides its message: the SDK kind, the finer reason, the desk, the HTTP status and
 * request id, the exit code {@code gaiadesk-cli} would have exited with, and the JSON that said so.
 * Built with {@link #builder()}; read through {@link GaiaDeskException}'s getters.
 */
public final class ErrorDetails {
    final @Nullable Integer exitCode;
    final String kind;
    final @Nullable String reason;
    final @Nullable String desk;
    final List<String> argv;
    final @Nullable JsonNode json;
    final @Nullable String requestId;
    final @Nullable Integer status;
    final @Nullable Double retryAfter;

    private ErrorDetails(Builder b) {
        this.exitCode = b.exitCode;
        this.kind = b.kind == null ? "cli_error" : b.kind;
        this.reason = b.reason;
        this.desk = b.desk;
        this.argv = Collections.unmodifiableList(new ArrayList<>(b.argv));
        this.json = b.json;
        this.requestId = b.requestId;
        this.status = b.status;
        this.retryAfter = b.retryAfter;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder with this one's fields, to change some. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.exitCode = exitCode;
        b.kind = kind;
        b.reason = reason;
        b.desk = desk;
        b.argv = new ArrayList<>(argv);
        b.json = json;
        b.requestId = requestId;
        b.status = status;
        b.retryAfter = retryAfter;
        return b;
    }

    /** Builds {@link ErrorDetails}. */
    public static final class Builder {
        @Nullable Integer exitCode;
        @Nullable String kind;
        @Nullable String reason;
        @Nullable String desk;
        List<String> argv = new ArrayList<>();
        @Nullable JsonNode json;
        @Nullable String requestId;
        @Nullable Integer status;
        @Nullable Double retryAfter;

        private Builder() {}

        /** The exit code gaiadesk-cli would have exited with (254 refused, 255 its own error, ...). */
        public Builder exitCode(@Nullable Integer v) { exitCode = v; return this; }
        /** The SDK kind ({@code usage}, {@code offline}, {@code refused}, ...). */
        public Builder kind(@Nullable String v) { kind = v; return this; }
        /** The finer cause ({@code offline}, {@code rate_limited}, {@code admin_denied}, ...). */
        public Builder reason(@Nullable String v) { reason = v; return this; }
        /** The desk the error concerned. */
        public Builder desk(@Nullable String v) { desk = v; return this; }
        /** What was being done: {@code ["POST /desks/123456789/exec"]}. */
        public Builder argv(List<String> v) { argv = new ArrayList<>(v); return this; }
        /** The JSON that reported the error (the error envelope, a result). */
        public Builder json(@Nullable JsonNode v) { json = v; return this; }
        /** The request's id ({@code req_…}). */
        public Builder requestId(@Nullable String v) { requestId = v; return this; }
        /** The HTTP status of the failed request. */
        public Builder status(@Nullable Integer v) { status = v; return this; }
        /** Seconds to wait before retrying (a 429's {@code Retry-After}). */
        public Builder retryAfter(@Nullable Double v) { retryAfter = v; return this; }
        /** The details. */
        public ErrorDetails build() { return new ErrorDetails(this); }
    }
}
