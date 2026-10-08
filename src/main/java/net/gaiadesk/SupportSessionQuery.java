package net.gaiadesk;

import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** Which support sessions ({@link GaiaDesk#supportSessions}): open ones by default, newest first. */
public final class SupportSessionQuery extends CallOptions<SupportSessionQuery> {
    @Nullable String state;
    @Nullable Integer limit;

    /** Open sessions (waiting for an agent, or being helped). */
    public SupportSessionQuery() {}

    /** Ended and expired sessions too. */
    public SupportSessionQuery all() {
        this.state = "all";
        return this;
    }

    /** At most this many (1-200; the API's default 50). */
    public SupportSessionQuery limit(int limit) {
        if (limit < 1 || limit > 200) throw Check.usage("limit is 1 to 200");
        this.limit = limit;
        return this;
    }
}
