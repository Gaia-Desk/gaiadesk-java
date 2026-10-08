package net.gaiadesk;

import java.time.Duration;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/**
 * What every call may be given: a desk token for this call only, a wake for a sleeping desk, a request
 * timeout, a {@link Cancellation} and an {@code Idempotency-Key}. Each operation's options class extends this,
 * so they chain: {@code new ExecOptions().shell(Shell.SH).wake(30)}. Options objects are mutable builders;
 * reuse one only for calls that should share every setting.
 *
 * @param <T> the options class itself (for chaining)
 */
public abstract class CallOptions<T extends CallOptions<T>> {
    @Nullable String deskToken;
    @Nullable Integer wake;
    @Nullable Duration requestTimeout;
    @Nullable Cancellation cancellation;
    @Nullable String idempotencyKey;

    /** Options with nothing set. */
    protected CallOptions() {}

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }

    /** The scoped agent token ({@code gdagt_…}) for this call, instead of the client's. */
    public T deskToken(String token) {
        if (token == null || token.trim().isEmpty()) throw Check.usage("deskToken must be a non-empty string (a scoped agent token, gdagt_…)");
        this.deskToken = token.trim();
        return self();
    }

    /** If the desk is asleep, ring it and wait up to this many seconds (0-120; {@code wake_s}). */
    public T wake(int seconds) {
        if (seconds < 0 || seconds > 120) throw Check.usage("wake is whole seconds, 0 to 120");
        this.wake = seconds;
        return self();
    }

    /** How long to wait for the answer's headers (a stream's or a held wait's body may come later); default: the client's. */
    public T requestTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw Check.usage("requestTimeout must be positive");
        this.requestTimeout = timeout;
        return self();
    }

    /** Cancel this call with {@link Cancellation#cancel()}. */
    public T cancellation(Cancellation cancellation) {
        this.cancellation = cancellation;
        return self();
    }

    /**
     * An {@code Idempotency-Key} for a POST (1-255 printable ASCII characters): a retry with the same key and the
     * same request within 24 hours gets the first answer again. Ignored on other methods.
     */
    public T idempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 255 || !key.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
            throw Check.usage("idempotencyKey is 1 to 255 printable ASCII characters");
        }
        this.idempotencyKey = key;
        return self();
    }
}
