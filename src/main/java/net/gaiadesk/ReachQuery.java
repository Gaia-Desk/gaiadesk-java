package net.gaiadesk;

import java.time.Instant;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** Which part of a desk's reach log ({@link GaiaDesk#reach}). */
public final class ReachQuery extends CallOptions<ReachQuery> {
    @Nullable Long since;
    @Nullable Integer limit;

    /** The last seven days, at most 200 transitions. */
    public ReachQuery() {}

    /** From this time (the log keeps thirty days). */
    public ReachQuery since(Instant since) {
        this.since = since.getEpochSecond();
        return this;
    }

    /** At most this many transitions, newest first (1-1000). */
    public ReachQuery limit(int limit) {
        if (limit < 1 || limit > 1000) throw Check.usage("limit is 1 to 1000");
        this.limit = limit;
        return this;
    }
}
