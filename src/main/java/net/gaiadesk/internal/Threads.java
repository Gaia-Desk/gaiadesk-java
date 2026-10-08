package net.gaiadesk.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** The SDK's default threads: daemon, named {@code gaiadesk-…}, made on first use. */
public final class Threads {
    private static final AtomicInteger N = new AtomicInteger();

    private Threads() {}

    private static ThreadFactory factory(String kind) {
        return r -> {
            Thread t = new Thread(r, "gaiadesk-" + kind + "-" + N.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static final class Pool {
        static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(factory("worker"));
    }

    private static final class Timer {
        static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(factory("timer"));
    }

    /** Runs async calls and stream readers. */
    public static ExecutorService executor() {
        return Pool.EXECUTOR;
    }

    /** Runs timeouts. */
    public static ScheduledExecutorService scheduler() {
        return Timer.SCHEDULER;
    }
}
