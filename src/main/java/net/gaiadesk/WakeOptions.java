package net.gaiadesk;

import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** How {@link GaiaDesk#wake} waits. */
public final class WakeOptions extends CallOptions<WakeOptions> {
    @Nullable Integer waitSeconds;

    /** Ring and answer at once. */
    public WakeOptions() {}

    /** Wait up to this many seconds (0-90) for the desk to come online, and say whether it woke. */
    public WakeOptions waitSeconds(int seconds) {
        if (seconds < 0 || seconds > 90) throw Check.usage("waitSeconds is 0 to 90");
        this.waitSeconds = seconds;
        return this;
    }
}
