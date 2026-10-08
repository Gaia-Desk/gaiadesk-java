package net.gaiadesk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.gaiadesk.ConnectionLostException;
import net.gaiadesk.ErrorDetails;
import net.gaiadesk.GaiaDeskException;
import net.gaiadesk.OperationFailedException;
import net.gaiadesk.ProtocolException;
import net.gaiadesk.RefusedException;
import net.gaiadesk.UnreachableException;
import net.gaiadesk.UsageException;
import org.jspecify.annotations.Nullable;

/** THE place that knows how the error envelope is spelled, and which class each kind is. */
public final class Errors {
    /** The SDK's kinds. */
    public static final Set<String> SDK_KINDS = new HashSet<>(Arrays.asList(
            "usage", "offline", "unknown_desk", "not_online", "refused", "network", "not_signed_in", "timeout",
            "connection_lost", "local", "failed", "interrupted", "protocol", "unreachable"));

    private Errors() {}

    /** The SDK kind for a kind and reason: the reason when it is an SDK kind, else the kind. */
    public static String sdkKind(String kind, @Nullable String reason) {
        return reason != null && SDK_KINDS.contains(reason) ? reason : kind;
    }

    /** The error class for an envelope kind (one of the six). */
    public static GaiaDeskException forKind(String kind, String message, ErrorDetails d) {
        switch (kind) {
            case "usage": return new UsageException(message, d);
            case "refused": return new RefusedException(message, d);
            case "connection_lost": return new ConnectionLostException(message, d);
            case "failed": return new OperationFailedException(message, d);
            case "protocol": return new ProtocolException(message, d);
            case "unreachable": return new UnreachableException(message, d);
            default: return new GaiaDeskException(message, d);
        }
    }

    /** gaiadesk-cli's exit code for a desk operation that failed with this kind. */
    public static int deskOpExit(String kind) {
        switch (kind) {
            case "refused": return 254;
            case "failed": return 1;
            case "interrupted": return 130;
            default: return 255;
        }
    }

    /** What an error envelope says. */
    public static final class Envelope {
        public final String kind;
        public final String message;
        public final @Nullable String reason;
        public final @Nullable String desk;

        Envelope(String kind, String message, @Nullable String reason, @Nullable String desk) {
            this.kind = kind;
            this.message = message;
            this.reason = reason;
            this.desk = desk;
        }
    }

    /** {@code {"error": {"kind", "message", "reason"?, "desk"?}}}, or null when the JSON is not an error (exec's own {@code "error": null} included). */
    public static @Nullable Envelope envelope(@Nullable JsonNode json) {
        if (json == null || !json.isObject()) return null;
        JsonNode e = json.get("error");
        if (e == null || !e.isObject() || !e.path("kind").isTextual()) return null;
        String reason = Json.text(e, "reason");
        String desk = Json.text(e, "desk");
        return new Envelope(e.get("kind").asText(), e.path("message").isTextual() ? e.get("message").asText() : "",
                reason == null || reason.isEmpty() ? null : reason, desk == null || desk.isEmpty() ? null : desk);
    }

    /** The details an envelope adds: the SDK kind, reason and desk. */
    public static ErrorDetails.Builder envelopeDetails(Envelope env, ErrorDetails.Builder b) {
        b.kind(sdkKind(env.kind, env.reason));
        if (env.reason != null) b.reason(env.reason);
        if (env.desk != null) b.desk(env.desk);
        return b;
    }

    /** The typed error of an envelope. */
    public static GaiaDeskException fromEnvelope(Envelope env, String fallback, ErrorDetails.Builder b) {
        return forKind(env.kind, env.message.isEmpty() ? fallback : env.message, envelopeDetails(env, b).build());
    }

    /** An exec result's (or an event's) {@code error}: null, or {@code {kind, message, reason?, desk?}}. */
    public static @Nullable ObjectNode execError(@Nullable JsonNode error) {
        if (error == null || !error.isObject()) return null;
        ObjectNode o = Json.object();
        o.put("kind", error.path("kind").isTextual() ? error.get("kind").asText() : "failed");
        o.put("message", error.path("message").isTextual() ? error.get("message").asText() : "");
        if (error.path("reason").isTextual()) o.put("reason", error.get("reason").asText());
        if (error.path("desk").isTextual()) o.put("desk", error.get("desk").asText());
        return o;
    }

    public static GaiaDeskException interrupted(String op) {
        return new GaiaDeskException(op + ": interrupted", ErrorDetails.builder().kind("interrupted").exitCode(130).argv(List.of(op)).build());
    }

    public static ProtocolException protocol(String message, String op, @Nullable JsonNode json, @Nullable String reason) {
        ErrorDetails.Builder b = ErrorDetails.builder().kind("protocol").exitCode(255).argv(List.of(op)).json(json);
        if (reason != null) b.reason(reason);
        return new ProtocolException(message, b.build());
    }

    /**
     * The typed error for a failed HTTP request: its error envelope (a sealed operation's opened first by
     * {@code open}), else a ProtocolException.
     */
    public static GaiaDeskException apiError(int status, @Nullable String requestIdHeader, @Nullable String retryAfterHeader, String text, String op,
            @Nullable UnaryOperator<JsonNode> open) {
        JsonNode json = Json.tryParse(text);
        if (open != null && json != null) json = open.apply(json);
        Double retryAfter = null;
        if (retryAfterHeader != null) {
            try {
                retryAfter = Double.parseDouble(retryAfterHeader.trim());
            } catch (NumberFormatException e) {
                retryAfter = null;
            }
        }
        Envelope env = envelope(json);
        if (env == null) {
            return new ProtocolException("the GaiaDesk API answered " + op + " with HTTP " + status + " and no error envelope",
                    ErrorDetails.builder().kind("protocol").exitCode(255).argv(List.of(op)).json(json)
                            .status(status).requestId(requestIdHeader).retryAfter(retryAfter).build());
        }
        String rid = json == null ? null : Json.text(json.get("error"), "request_id");
        ErrorDetails.Builder b = ErrorDetails.builder().exitCode(deskOpExit(env.kind)).argv(List.of(op)).json(json).status(status)
                .requestId(rid != null ? rid : requestIdHeader).retryAfter(retryAfter);
        return fromEnvelope(env, "HTTP " + status, b);
    }

    /** The same error with other JSON (the class kept; one without the usual constructor is returned as it is). */
    public static GaiaDeskException withJson(GaiaDeskException e, JsonNode json) {
        ErrorDetails d = e.getDetails().toBuilder().json(json).build();
        try {
            GaiaDeskException copy = e.getClass().getConstructor(String.class, ErrorDetails.class, Throwable.class)
                    .newInstance(e.getMessage(), d, e.getCause());
            copy.setStackTrace(e.getStackTrace());
            return copy;
        } catch (ReflectiveOperationException | RuntimeException x) {
            return e;
        }
    }
}
