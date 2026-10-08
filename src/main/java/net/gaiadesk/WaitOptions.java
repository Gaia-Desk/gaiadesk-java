package net.gaiadesk;

import java.time.Duration;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** How long {@link GaiaDesk#waitJob} waits. */
public final class WaitOptions extends CallOptions<WaitOptions> {
    @Nullable Long timeoutSecs;

    /** Wait until the job ends, however long. */
    public WaitOptions() {}

    /** Give up after this long ({@code 0}: answer at once), and answer the job as it stands, {@code timedOut}. */
    public WaitOptions timeout(Duration timeout) {
        this.timeoutSecs = Check.seconds(timeout.toMillis() / 1000.0, "timeout");
        return this;
    }

    /** Give up after this many seconds. */
    public WaitOptions timeoutSeconds(double seconds) {
        this.timeoutSecs = Check.seconds(seconds, "timeout");
        return this;
    }

    /** Give up after this long: {@code 90}, {@code 30s}, {@code 10m}, {@code 2h}. */
    public WaitOptions timeout(String duration) {
        this.timeoutSecs = Check.seconds(duration, "timeout");
        return this;
    }
}
