package net.gaiadesk;

import java.util.ArrayList;
import java.util.List;

/**
 * Cancels calls in flight: give one to a call's options ({@link CallOptions#cancellation(Cancellation)}), then
 * {@link #cancel()} from any thread. The call stops what it is doing (closing its request) and throws a
 * {@link GaiaDeskException} of kind {@code interrupted} (exit code 130); a stream ends with exit code 130.
 * One cancellation may cover many calls; once cancelled it stays cancelled.
 *
 * <p>The futures of {@link AsyncGaiaDesk} cancel their own call when {@code cancel(true)} is called on them, and
 * an interrupted thread interrupts the blocking call it is in.
 */
public final class Cancellation {
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean cancelled;

    /** A new, uncancelled cancellation. */
    public Cancellation() {}

    /** Cancel every call using this, now and from now on. */
    public void cancel() {
        List<Runnable> run;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            run = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable r : run) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // A listener's failure must not stop the others.
            }
        }
    }

    /** Was {@link #cancel()} called? */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * Run {@code r} when this is cancelled (at once, if it already is). The returned handle removes it. For the
     * SDK's own use, and for code that wants to stop its own work with a call.
     */
    public Registration onCancel(Runnable r) {
        boolean now;
        synchronized (this) {
            now = cancelled;
            if (!now) listeners.add(r);
        }
        if (now) r.run();
        return () -> {
            synchronized (Cancellation.this) {
                listeners.remove(r);
            }
        };
    }

    /** A listener added with {@link #onCancel(Runnable)}; {@link #close()} removes it. */
    public interface Registration extends AutoCloseable {
        /** Remove the listener (it is not run). */
        @Override
        void close();
    }
}
