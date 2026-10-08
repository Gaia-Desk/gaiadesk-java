package net.gaiadesk;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/**
 * When and how a failed request is sent again: the one rule every GaiaDesk SDK follows. A request is sent again
 * only when that cannot run anything twice:
 *
 * <ul>
 *   <li>the connection was never made (DNS, refused, the TLS handshake broke off, no local socket or pipe): any
 *       method, nothing was sent (a connect that timed out and a certificate or pin refused are final);
 *   <li>the connection was lost after sending (closed or reset before any answer), or the answer was 502, 503 or
 *       504: {@code GET}s only (reads); a 503 whose reason is permanent ({@code api_disabled},
 *       {@code desk_ops_disabled}, {@code local_api_off}) is final;
 *   <li>429 ({@code rate_limited}, {@code desk_busy}) and 409 {@code idempotency_key_in_flight}: any method, the
 *       server refused it before acting.
 * </ul>
 *
 * Timeouts are never retried, nor anything whose answer had begun; a call that changes something (POST, PUT,
 * DELETE) is never sent again after it may have reached the server, and an {@code Idempotency-Key} does not make
 * it retryable. 429 and 503 wait for {@code Retry-After}; one longer than {@link #getMaxRetryWait() maxRetryWait}
 * (default 60 s) is not waited for: the error is thrown at once, with {@link GaiaDeskException#getRetryAfter()}.
 * Otherwise the wait is {@code min(maxDelay, baseDelay × 2^n) × a random 0.5–1.0}. Each retry of a sealed
 * operation is sealed afresh; a cancellation during a wait cancels at once.
 *
 * <p>The default: 2 retries (3 attempts in all), 250 ms base, 8 s at most, Retry-After waited up to 60 s.
 * {@link #none()} turns retries off.
 */
public final class RetryPolicy {
    /** The default base delay: 250 ms. */
    public static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(250);
    /** The default longest backoff: 8 s. */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(8);
    /** The default longest {@code Retry-After} waited for: 60 s. */
    public static final Duration DEFAULT_MAX_RETRY_WAIT = Duration.ofSeconds(60);

    private static final RetryPolicy NONE = new RetryPolicy(0, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MAX_RETRY_WAIT);
    private static final RetryPolicy DEFAULT = new RetryPolicy(2, DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MAX_RETRY_WAIT);
    private static final Set<String> PERMANENT_UNAVAILABLE = new HashSet<>(Arrays.asList("api_disabled", "desk_ops_disabled", "local_api_off"));

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration maxRetryWait;

    private RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay, Duration maxRetryWait) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.maxRetryWait = maxRetryWait;
    }

    private static Duration check(@Nullable Duration d, String name) {
        if (d == null || d.isNegative()) throw Check.usage("retry " + name + " must be zero or more, not " + d);
        return d;
    }

    /**
     * {@code maxRetries} tries after the first, backing off from {@code baseDelay} up to {@code maxDelay}; a
     * {@code Retry-After} is waited for up to {@code maxRetryWait}. Negative values are a {@link UsageException}.
     */
    public static RetryPolicy of(int maxRetries, Duration baseDelay, Duration maxDelay, Duration maxRetryWait) {
        if (maxRetries < 0) throw Check.usage("retry maxRetries must be zero or more, not " + maxRetries);
        return new RetryPolicy(maxRetries, check(baseDelay, "baseDelay"), check(maxDelay, "maxDelay"), check(maxRetryWait, "maxRetryWait"));
    }

    /** {@link #of(int, Duration, Duration, Duration)} with the default {@code maxRetryWait} (60 s). */
    public static RetryPolicy of(int maxRetries, Duration baseDelay, Duration maxDelay) {
        return of(maxRetries, baseDelay, maxDelay, DEFAULT_MAX_RETRY_WAIT);
    }

    /** The default: 2 retries, 250 ms base, 8 s at most, a {@code Retry-After} waited for up to 60 s. */
    public static RetryPolicy defaults() {
        return DEFAULT;
    }

    /** Never retry. */
    public static RetryPolicy none() {
        return NONE;
    }

    /** Tries after the first. */
    public int getMaxRetries() {
        return maxRetries;
    }

    /** The first backoff (before jitter). */
    public Duration getBaseDelay() {
        return baseDelay;
    }

    /** The longest backoff (before jitter). */
    public Duration getMaxDelay() {
        return maxDelay;
    }

    /** The longest {@code Retry-After} waited for; a longer one is thrown at once. */
    public Duration getMaxRetryWait() {
        return maxRetryWait;
    }

    /**
     * How long to wait before retry {@code retry} (0 for the first retry), given the answer's honoured
     * {@code Retry-After} in seconds (or null); -1 when it is not to be retried (out of retries, or a
     * {@code Retry-After} longer than {@link #getMaxRetryWait()}).
     */
    public long delayMillis(int retry, @Nullable Double retryAfterSeconds) {
        if (retry >= maxRetries) return -1;
        if (retryAfterSeconds != null) {
            long ms = (long) Math.ceil(Math.max(0, retryAfterSeconds) * 1000);
            return ms > maxRetryWait.toMillis() ? -1 : ms;
        }
        return backoffMillis(retry, 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
    }

    /** {@code min(maxDelay, baseDelay × 2^retry) × jitter} ({@code jitter} in [0.5, 1.0]). */
    public long backoffMillis(int retry, double jitter) {
        double ms = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * Math.pow(2, Math.min(retry, 30)));
        return (long) (ms * jitter);
    }

    /** Whether {@code e}, the failure of one attempt at {@code method}, may be sent again. */
    static boolean retryable(GaiaDeskException e, String method) {
        Integer status = e.getStatus();
        String reason = e.getReason();
        // The SDK's own timeouts (no HTTP status): the request may be running; never sent again.
        if (status == null && ("timeout".equals(e.getKind()) || "timeout".equals(reason))) return false;
        if (status == null && neverConnected(e)) return true;
        if ((status != null && status == 429) || "rate_limited".equals(reason) || "desk_busy".equals(reason)) return true;
        if (status != null && status == 409 && "idempotency_key_in_flight".equals(reason)) return true;
        if (!method.equals("GET")) return false;
        if (status == null) return e instanceof UnreachableException && "network".equals(e.getKind());
        if (status == 502 || status == 504) return true;
        return status == 503 && (reason == null || !PERMANENT_UNAVAILABLE.contains(reason));
    }

    /** The {@code Retry-After} this error is waited for: a 429's or a 503's (else null: backoff). */
    static @Nullable Double honouredRetryAfter(GaiaDeskException e) {
        Integer status = e.getStatus();
        boolean honours = (status != null && (status == 429 || status == 503)) || "rate_limited".equals(e.getReason()) || "desk_busy".equals(e.getReason());
        return honours ? e.getRetryAfter() : null;
    }

    /**
     * Whether the request failed before any byte of it was written: the name did not resolve, the connect was
     * refused (or the local socket or pipe is not there), the TLS handshake broke off. Not a connect that timed out,
     * and not a certificate or pin refused (both final).
     */
    static boolean neverConnected(GaiaDeskException e) {
        if (!(e instanceof UnreachableException) || e instanceof FingerprintMismatchException) return false;
        boolean connect = false;
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return false;
            if (t instanceof GeneralSecurityException || t instanceof SSLPeerUnverifiedException) return false;
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof UnresolvedAddressException
                    || t instanceof SSLHandshakeException) {
                connect = true;
            }
            if (t.getCause() == t) break;
        }
        return connect;
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxRetries=" + maxRetries + ", baseDelay=" + baseDelay + ", maxDelay=" + maxDelay + ", maxRetryWait=" + maxRetryWait + "}";
    }
}
