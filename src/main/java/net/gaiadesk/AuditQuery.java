package net.gaiadesk;

import java.time.Instant;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** Which audit events ({@link GaiaDesk#audit}): all filters optional; newest first. */
public final class AuditQuery extends CallOptions<AuditQuery> {
    @Nullable String desk;
    @Nullable String actor;
    @Nullable String action;
    @Nullable String token;
    @Nullable Long sinceMs;
    @Nullable Long untilMs;
    @Nullable Integer limit;

    /** Every event the caller may see (the API's first 100). */
    public AuditQuery() {}

    /** Only events about this desk (one of the caller's own). */
    public AuditQuery desk(String desk) {
        this.desk = Check.desk(desk);
        return this;
    }

    /** Only events by this actor id (an email, a token id). */
    public AuditQuery actor(String actor) {
        this.actor = actor;
        return this;
    }

    /** Only this action, or every action under it when it ends in {@code .*} ({@code api.*}). */
    public AuditQuery action(String action) {
        this.action = action;
        return this;
    }

    /** Only events by this agent token id or API key id. */
    public AuditQuery token(String token) {
        this.token = token;
        return this;
    }

    /** Only events at or after this time. */
    public AuditQuery since(Instant since) {
        this.sinceMs = since.toEpochMilli();
        return this;
    }

    /** Only events at or before this time. */
    public AuditQuery until(Instant until) {
        this.untilMs = until.toEpochMilli();
        return this;
    }

    /** At most this many per page (1-500; the API's default 100). */
    public AuditQuery limit(int limit) {
        if (limit < 1 || limit > 500) throw Check.usage("limit is 1 to 500");
        this.limit = limit;
        return this;
    }
}
