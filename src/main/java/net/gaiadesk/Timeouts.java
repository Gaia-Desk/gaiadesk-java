package net.gaiadesk;

import java.time.Duration;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/**
 * How long the SDK waits on the network before giving up, so a server or proxy that stops answering (a dropped
 * connection that is never closed, a half-open socket, a stalled proxy) is a typed error, never a hang. Applies to
 * every transport (the hosted API, the local API, the LAN gateway). Immutable: each setter returns a copy.
 *
 * <ul>
 *   <li>{@link #getResponseTimeout() responseTimeout} (default 16 minutes, above the API's 15-minute limit on a
 *       call: a buffered exec answers only when its command ends): the longest wait for an answer to begin (its
 *       status and headers), sending the request included. Exceeded: {@link UnreachableException}, kind
 *       {@code timeout}. A call's {@link CallOptions#requestTimeout(Duration)} replaces it for that call.
 *   <li>{@link #getIdleTimeout() idleTimeout} (default 90 s: the API's streams and held waits send a keep-alive
 *       every 15 s): the longest silence while reading an answer's body (a JSON result, an error body, a download,
 *       an event stream). It bounds each read, not the whole body: a long download that keeps flowing never times
 *       out. Exceeded: {@link ConnectionLostException}, kind {@code timeout} (a stream ends with that error in its
 *       {@link Exit}: kind {@code connection_lost}, reason {@code timeout}, exit code 255).
 * </ul>
 *
 * {@code null} is no limit; zero or a negative duration is a {@link UsageException}. {@link #none()} turns both off.
 */
public final class Timeouts {
    /** The default wait for an answer to begin: 16 minutes. */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(16);
    /** The default silence allowed while reading a body: 90 seconds. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(90);

    private static final Timeouts DEFAULT = new Timeouts(DEFAULT_RESPONSE_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    private static final Timeouts NONE = new Timeouts(null, null);

    private final @Nullable Duration responseTimeout;
    private final @Nullable Duration idleTimeout;

    private Timeouts(@Nullable Duration responseTimeout, @Nullable Duration idleTimeout) {
        this.responseTimeout = responseTimeout;
        this.idleTimeout = idleTimeout;
    }

    private static @Nullable Duration check(@Nullable Duration d, String name) {
        if (d != null && (d.isZero() || d.isNegative())) throw Check.usage("timeouts." + name + " must be positive (or null: no limit), not " + d);
        return d;
    }

    /** Both timeouts ({@code null}: no limit). */
    public static Timeouts of(@Nullable Duration responseTimeout, @Nullable Duration idleTimeout) {
        return new Timeouts(check(responseTimeout, "responseTimeout"), check(idleTimeout, "idleTimeout"));
    }

    /** The defaults: 16 minutes for an answer to begin, 90 s of silence while reading one. */
    public static Timeouts defaults() {
        return DEFAULT;
    }

    /** No limits at all: a peer that stops answering is waited for as long as the connection stays open. */
    public static Timeouts none() {
        return NONE;
    }

    /** A copy with this response timeout ({@code null}: no limit). */
    public Timeouts responseTimeout(@Nullable Duration timeout) {
        return new Timeouts(check(timeout, "responseTimeout"), idleTimeout);
    }

    /** A copy with this idle timeout ({@code null}: no limit). */
    public Timeouts idleTimeout(@Nullable Duration timeout) {
        return new Timeouts(responseTimeout, check(timeout, "idleTimeout"));
    }

    /** The longest wait for an answer to begin, sending the request included; null: no limit. */
    public @Nullable Duration getResponseTimeout() {
        return responseTimeout;
    }

    /** The longest silence while reading an answer's body; null: no limit. */
    public @Nullable Duration getIdleTimeout() {
        return idleTimeout;
    }

    @Override
    public String toString() {
        return "Timeouts{responseTimeout=" + responseTimeout + ", idleTimeout=" + idleTimeout + "}";
    }
}
