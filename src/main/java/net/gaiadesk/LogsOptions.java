package net.gaiadesk;

import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** How much of a job's log ({@link GaiaDesk#jobLogs}, {@link GaiaDesk#followJobLogs}). */
public final class LogsOptions extends CallOptions<LogsOptions> {
    @Nullable Long tail;

    /** All the desk keeps. */
    public LogsOptions() {}

    /** Only the last this many bytes. */
    public LogsOptions tail(long bytes) {
        if (bytes < 0) throw Check.usage("tail is a number of bytes");
        this.tail = bytes;
        return this;
    }
}
