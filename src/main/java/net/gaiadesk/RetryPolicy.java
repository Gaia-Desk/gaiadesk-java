package net.gaiadesk;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * When and how a failed request is tried again. A request is retried only when trying again cannot do the
 * operation twice:
 *
 * <ul>
 *   <li>the connection could not be opened (nothing was sent);
 *   <li>429 {@code rate_limited}, {@code desk_busy} or {@code idempotency_key_in_flight} (the operation was not
 *       run), waiting the answer's {@code Retry-After} when it has one;
 *   <li>502, 503 or 504 answering a {@code GET} (reads only: listing, stats, logs, a wait, a download).
 * </ul>
 *
 * Commands, job starts, uploads and token changes are never re-sent after the desk may have run them. Between
 * tries it waits an exponential backoff with full jitter ({@code base × 2^n}, at most {@code maxDelay}); a
 * {@code Retry-After} longer than {@code maxDelay} is not waited for: the error is thrown, with
 * {@link GaiaDeskException#getRetryAfter()}. Streams are retried only before they start.
 *
 * <p>The default: 2 retries, 500 ms base, 30 s at most. {@link #none()} turns retries off.
 */
public final class RetryPolicy {
    private static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO, Duration.ZERO);
    private static final RetryPolicy DEFAULT = new RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(30));

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;

    private RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    /** {@code maxRetries} tries after the first, backing off from {@code baseDelay} up to {@code maxDelay}. */
    public static RetryPolicy of(int maxRetries, Duration baseDelay, Duration maxDelay) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        if (baseDelay.isNegative() || maxDelay.isNegative()) throw new IllegalArgumentException("delays must be >= 0");
        return new RetryPolicy(maxRetries, baseDelay, maxDelay);
    }

    /** The default: 2 retries, 500 ms base, 30 s at most. */
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

    /** The first backoff's ceiling. */
    public Duration getBaseDelay() {
        return baseDelay;
    }

    /** The longest wait between tries. */
    public Duration getMaxDelay() {
        return maxDelay;
    }

    /**
     * How long to wait before retry {@code retry} (0 for the first retry), given the answer's
     * {@code Retry-After} in seconds (or null); -1 when it should not be retried.
     */
    public long delayMillis(int retry, @Nullable Double retryAfterSeconds) {
        if (retry >= maxRetries) return -1;
        long max = maxDelay.toMillis();
        if (retryAfterSeconds != null) {
            long ms = (long) Math.ceil(Math.max(0, retryAfterSeconds) * 1000);
            return ms > max ? -1 : ms;
        }
        long ceiling = Math.min(max, baseDelay.toMillis() << Math.min(retry, 20));
        return ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxRetries=" + maxRetries + ", baseDelay=" + baseDelay + ", maxDelay=" + maxDelay + "}";
    }
}
