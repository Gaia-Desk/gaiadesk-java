package net.gaiadesk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** A call's cancellation (the options' and the async future's), and running a call as a future. */
final class Calls {
    private static final ThreadLocal<Cancellation> CURRENT = new ThreadLocal<>();

    private Calls() {}

    /** One call's scope: a cancellation that the options' cancellation and the running future both cancel. */
    static final class Scope implements AutoCloseable {
        final Cancellation cancel = new Cancellation();
        private final List<Cancellation.Registration> regs = new ArrayList<>();

        void link(@Nullable Cancellation parent) {
            if (parent != null) regs.add(parent.onCancel(cancel::cancel));
        }

        @Override
        public void close() {
            for (Cancellation.Registration r : regs) r.close();
        }
    }

    static Scope scope(@Nullable CallOptions<?> o) {
        Scope s = new Scope();
        s.link(o == null ? null : o.cancellation);
        s.link(CURRENT.get());
        return s;
    }

    /** The cancellation a stream should also obey (the options', or the running future's). */
    static @Nullable Cancellation outer(@Nullable CallOptions<?> o) {
        return o != null && o.cancellation != null ? o.cancellation : CURRENT.get();
    }

    /** A future whose {@code cancel} cancels the call it runs. */
    static final class CallFuture<T> extends CompletableFuture<T> {
        final Cancellation cancel = new Cancellation();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean done = super.cancel(mayInterruptIfRunning);
            cancel.cancel();
            return done;
        }
    }

    static <T> CompletableFuture<T> async(Executor executor, Supplier<T> call) {
        CallFuture<T> f = new CallFuture<>();
        executor.execute(() -> {
            if (f.isDone()) return;
            Cancellation prev = CURRENT.get();
            CURRENT.set(f.cancel);
            try {
                f.complete(call.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                if (prev == null) CURRENT.remove();
                else CURRENT.set(prev);
            }
        });
        return f;
    }
}
