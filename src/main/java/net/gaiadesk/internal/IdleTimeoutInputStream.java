package net.gaiadesk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A response body whose every read must make progress within a time limit. A read that waits longer abandons the
 * connection ({@code abandon}: closing it, so it is never reused, and so the blocked read returns) and throws
 * {@code timedOut}'s error. It bounds each read, not the whole body: a body that keeps flowing never times out.
 *
 * <p>One watchdog task per stream at a time (on {@link Threads#scheduler()}), re-armed from the last read's
 * deadline, so a long download costs no timer per read.
 */
public final class IdleTimeoutInputStream extends InputStream {
    private final InputStream in;
    private final long idleNanos;
    private final Runnable abandon;
    private final Supplier<? extends RuntimeException> timedOut;
    private final Object lock = new Object();
    private long deadline;
    private boolean reading;
    private boolean armed;
    private boolean closed;
    private volatile boolean fired;

    public IdleTimeoutInputStream(InputStream in, Duration idle, Runnable abandon, Supplier<? extends RuntimeException> timedOut) {
        this.in = in;
        this.idleNanos = Math.max(1, idle.toNanos());
        this.abandon = abandon;
        this.timedOut = timedOut;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        for (;;) {
            int n = read(one, 0, 1);
            if (n < 0) return -1;
            if (n == 1) return one[0] & 0xff;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (fired) throw timedOut.get();
        if (len == 0) return 0;
        arm();
        try {
            return in.read(b, off, len);
        } catch (IOException | RuntimeException e) {
            if (fired) {
                RuntimeException t = timedOut.get();
                t.addSuppressed(e);
                throw t;
            }
            throw e;
        } finally {
            synchronized (lock) {
                reading = false;
            }
        }
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    private void arm() {
        synchronized (lock) {
            deadline = System.nanoTime() + idleNanos;
            reading = true;
            if (!armed && !closed) {
                armed = true;
                Threads.scheduler().schedule(this::check, idleNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    private void check() {
        synchronized (lock) {
            if (closed || !reading) {
                armed = false;
                return;
            }
            long left = deadline - System.nanoTime();
            if (left > 0) {
                Threads.scheduler().schedule(this::check, left, TimeUnit.NANOSECONDS);
                return;
            }
            armed = false;
            fired = true;
        }
        abandon.run();
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            closed = true;
        }
        in.close();
    }
}
