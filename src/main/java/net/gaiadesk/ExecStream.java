package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.gaiadesk.internal.E2eAnswers;
import net.gaiadesk.internal.Errors;
import net.gaiadesk.internal.Json;
import net.gaiadesk.internal.SseEvent;
import net.gaiadesk.internal.SseParser;
import net.gaiadesk.internal.Utf8Stream;
import net.gaiadesk.model.ExecError;
import net.gaiadesk.model.ExecExit;
import org.jspecify.annotations.Nullable;

/**
 * A command's output ({@link GaiaDesk#execStream}) or a job's log ({@link GaiaDesk#followJobLogs}) as it comes:
 * chunks of stdout and stderr, then how it ended. It starts at once, on its own thread; read it one way:
 *
 * <ul>
 *   <li>iterate it ({@code for (Chunk c : stream)}), blocking for each chunk, then {@link #exit()};
 *   <li>{@link #collect()} it: everything, and the exit;
 *   <li>subscribe to {@link #asPublisher()} ({@code java.util.concurrent.Flow}; in Kotlin, {@code asFlow()} from
 *       kotlinx-coroutines-jdk9).
 * </ul>
 *
 * Failures never throw from here: they end the stream, and {@link Exit#getError()} says why (refused before it
 * started, the desk lost, ...). {@link #kill()} (or {@link #close()} before the end) closes the request: the
 * server stops the command, or stops following the job; the exit code is then 130. Over the HTTP transports
 * stdin is given up front ({@link ExecOptions#stdin(String)}): there is nothing to write to.
 */
public final class ExecStream implements Iterable<Chunk>, AutoCloseable {
    private static final Object END = new Object();

    /** How a started stream reads: its response, and how its sealed events open. */
    static final class Start {
        final Core.Res res;
        final E2eAnswers.@Nullable SseUnsealer unsealer;

        Start(Core.Res res, E2eAnswers.@Nullable SseUnsealer unsealer) {
            this.res = res;
            this.unsealer = unsealer;
        }
    }

    private final List<String> argv;
    private final boolean logs;
    private final String jobName;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Exit> exit = new CompletableFuture<>();
    private final Cancellation cancel = new Cancellation();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final Executor executor;
    private volatile boolean killed;

    ExecStream(String op, boolean logs, String jobName, Function<Cancellation, Start> start, Executor executor, @Nullable Cancellation outer) {
        this.argv = List.of(op);
        this.logs = logs;
        this.jobName = jobName;
        this.executor = executor;
        Cancellation.Registration reg = outer == null ? null : outer.onCancel(this::kill);
        executor.execute(() -> {
            Exit e = run(start);
            if (reg != null) reg.close();
            queue.add(END);
            exit.complete(e);
        });
    }

    /** What is running: {@code ["POST /desks/123456789/exec"]}. */
    public List<String> getArgv() {
        return argv;
    }

    private Exit run(Function<Cancellation, Start> start) {
        Start s = null;
        try {
            s = start.apply(cancel);
            return read(s);
        } catch (GaiaDeskException e) {
            if (killed) return killedExit();
            return exitForError(e);
        } catch (RuntimeException e) {
            if (killed) return killedExit();
            return exitForError(new UnreachableException("the GaiaDesk API could not be reached: " + e.getMessage(),
                    ErrorDetails.builder().kind("network").reason("network").exitCode(255).argv(argv).build(), e));
        } finally {
            if (s != null) s.res.close();
        }
    }

    private static Exit killedExit() {
        return new Exit(130, "interrupted", null, null);
    }

    private Exit read(Start s) {
        InputStream in = s.res.http.body();
        Cancellation.Registration reg = cancel.onCancel(s.res::close);
        Utf8Stream dec = new Utf8Stream();
        SseParser parser = new SseParser();
        Reader reader = new Reader();
        try {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                byte[] got = new byte[n];
                System.arraycopy(buf, 0, got, 0, n);
                for (SseEvent ev : parser.feed(dec.decode(got))) {
                    Exit done = reader.on(ev, s.unsealer);
                    if (done != null) return done;
                }
            }
            List<SseEvent> rest = parser.feed(dec.flush());
            rest.addAll(parser.end());
            for (SseEvent ev : rest) {
                Exit done = reader.on(ev, s.unsealer);
                if (done != null) return done;
            }
            return reader.end();
        } catch (IOException e) {
            if (killed) return killedExit();
            return exitForError(new ConnectionLostException("the event stream broke: " + e.getMessage(),
                    ErrorDetails.builder().kind("connection_lost").exitCode(255).argv(argv).build(), e));
        } finally {
            reg.close();
        }
    }

    /** The events of one stream, in order: chunks pushed, the end returned. */
    private final class Reader {
        private @Nullable ObjectNode last;

        @Nullable Exit on(SseEvent sse, E2eAnswers.@Nullable SseUnsealer unsealer) {
            if (unsealer == null) return one(sse);
            for (SseEvent ev : unsealer.map(sse)) {
                Exit done = one(ev);
                if (done != null) return done;
            }
            return null;
        }

        /** The JSON of one SSE event, with the SSE name as its {@code event} when the JSON has none. */
        private @Nullable ObjectNode object(SseEvent ev) {
            JsonNode v = Json.tryParse(ev.data);
            if (v == null || !v.isObject()) return null;
            ObjectNode o = (ObjectNode) v;
            if (!o.path("event").isTextual()) o.put("event", ev.event);
            return o;
        }

        private @Nullable Exit one(SseEvent sse) {
            ObjectNode o = object(sse);
            if (o == null) return null;
            String event = o.get("event").asText();
            if (!logs) {
                if ((event.equals("stdout") || event.equals("stderr")) && o.path("data").isTextual()) {
                    push(event.equals("stdout") ? Chunk.Stream.STDOUT : Chunk.Stream.STDERR, o.get("data").asText());
                } else if (event.equals("exit") || event.equals("error")) {
                    last = o;
                }
                return null;
            }
            switch (event) {
                case "output":
                    if (o.path("data").isTextual()) push(Chunk.Stream.STDOUT, o.get("data").asText());
                    return null;
                case "end": {
                    JsonNode job = o.path("job");
                    String name = job.path("name").isTextual() ? job.get("name").asText() : jobName;
                    String tail = job.path("exit_code").isNumber() ? "job " + name + " exited (exit " + job.get("exit_code").asInt() + ")"
                            : "job " + name + " " + (job.path("state").isTextual() ? job.get("state").asText() : "ended");
                    return new Exit(0, tail, null, null);
                }
                case "interrupted":
                    return new Exit(0, "stopped following; the job goes on", null, null);
                case "error": {
                    ObjectNode err = Errors.execError(o.get("error"));
                    if (err == null) {
                        err = Json.object();
                        err.put("kind", "protocol");
                        err.put("message", "the desk reported an error");
                    }
                    String kind = err.get("kind").asText();
                    return new Exit(Errors.deskOpExit(kind), err.get("message").asText(), null, Json.convert(err, ExecError.class));
                }
                default:
                    return null;
            }
        }

        Exit end() {
            if (logs) return lostExit("the event stream ended before the job did");
            ObjectNode l = last;
            if (l == null) return lostExit("the event stream ended before the command did");
            return exitFromEvent(l);
        }
    }

    private void push(Chunk.Stream stream, String text) {
        queue.add(new Chunk(stream, text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Exit lostExit(String message) {
        ObjectNode err = Json.object();
        err.put("kind", "connection_lost");
        err.put("message", message);
        return new Exit(255, message, null, Json.convert(err, ExecError.class));
    }

    /** The Exit of a stream's last {@code exit} or {@code error} event. */
    static Exit exitFromEvent(ObjectNode last) {
        String event = last.get("event").asText();
        if (event.equals("exit")) {
            ObjectNode rest = last.deepCopy();
            rest.remove("event");
            ObjectNode error = Errors.execError(rest.get("error"));
            if (error != null) rest.set("error", error);
            else rest.putNull("error");
            Integer code = rest.path("exit").isNumber() ? rest.get("exit").asInt() : null;
            String tail = error != null ? error.get("message").asText() : "";
            return new Exit(code, tail, Json.convert(rest, ExecExit.class), error == null ? null : Json.convert(error, ExecError.class));
        }
        ObjectNode error = Errors.execError(last.get("error"));
        Integer code = last.path("exit").isNumber() ? last.get("exit").asInt() : null;
        return new Exit(code, error != null ? error.get("message").asText() : "", null, error == null ? null : Json.convert(error, ExecError.class));
    }

    /** The Exit for an error that ended (or prevented) a stream. */
    static Exit exitForError(GaiaDeskException e) {
        Errors.Envelope env = Errors.envelope(e.getJson());
        ObjectNode src = Json.object();
        if (env != null) {
            src.put("kind", env.kind);
            src.put("message", env.message.isEmpty() ? e.getMessage() : env.message);
            if (env.reason != null) src.put("reason", env.reason);
            if (env.desk != null) src.put("desk", env.desk);
        } else {
            src.put("kind", e.getKind().equals("network") ? "unreachable" : e.getKind());
            src.put("message", String.valueOf(e.getMessage()));
            if (e.getReason() != null) src.put("reason", e.getReason());
            if (e.getDesk() != null) src.put("desk", e.getDesk());
        }
        ObjectNode err = Errors.execError(src);
        return new Exit(e.getExitCode(), String.valueOf(e.getMessage()), null, err == null ? null : Json.convert(err, ExecError.class));
    }

    // ───────────────────────────── reading ─────────────────────────────

    private @Nullable Chunk take() {
        Object o;
        try {
            o = queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
            throw Errors.interrupted(argv.get(0));
        }
        if (o == END) {
            queue.add(END);
            return null;
        }
        return (Chunk) o;
    }

    /** The chunks, blocking for each until the stream ends. Read the stream once (one iterator, or one publisher). */
    @Override
    public Iterator<Chunk> iterator() {
        return new Iterator<Chunk>() {
            private @Nullable Chunk next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                if (done) return false;
                next = take();
                if (next == null) done = true;
                return next != null;
            }

            @Override
            public Chunk next() {
                if (!hasNext()) throw new NoSuchElementException();
                Chunk c = next;
                next = null;
                if (c == null) throw new NoSuchElementException();
                return c;
            }
        };
    }

    /** Read it to the end: all of stdout and stderr, and how it ended. */
    public StreamResult collect() {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        for (Chunk c : this) (c.getStream() == Chunk.Stream.STDOUT ? out : err).append(c.getText());
        return new StreamResult(out.toString(), err.toString(), exit());
    }

    /** How it ended, once it has (blocking). An interrupted wait kills the stream and throws an {@code interrupted} error. */
    public Exit exit() {
        try {
            return exit.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
            throw Errors.interrupted(argv.get(0));
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** How it ended, when it has. */
    public CompletableFuture<Exit> exitAsync() {
        return exit.thenApply(e -> e);
    }

    /** Has it ended? */
    public boolean isDone() {
        return exit.isDone();
    }

    /** Stop: closes the request (the server stops the command, or stops following the job). */
    public void kill() {
        if (exit.isDone()) return;
        killed = true;
        cancel.cancel();
    }

    /** {@link #kill()} if it is still running. */
    @Override
    public void close() {
        kill();
    }

    /**
     * The chunks as a {@link Flow.Publisher} for one subscriber, honouring its demand; it completes when the
     * stream ends (how is {@link #exit()}). Cancelling the subscription kills the stream. Kotlin:
     * {@code stream.asPublisher().asFlow()} (kotlinx-coroutines-jdk9).
     */
    public Flow.Publisher<Chunk> asPublisher() {
        return subscriber -> {
            if (!consumed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {}

                    @Override
                    public void cancel() {}
                });
                subscriber.onError(new IllegalStateException("an ExecStream's publisher takes one subscriber"));
                return;
            }
            AtomicLong demand = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();
            Object signal = new Object();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0) {
                        cancelled.set(true);
                        subscriber.onError(new IllegalArgumentException("request(" + n + "): demand must be positive (§3.9)"));
                        return;
                    }
                    demand.getAndUpdate(d -> d + n < 0 ? Long.MAX_VALUE : d + n);
                    synchronized (signal) {
                        signal.notifyAll();
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                    kill();
                    synchronized (signal) {
                        signal.notifyAll();
                    }
                }
            });
            executor.execute(() -> {
                try {
                    for (;;) {
                        synchronized (signal) {
                            while (demand.get() == 0 && !cancelled.get()) signal.wait();
                        }
                        if (cancelled.get()) return;
                        Chunk c = take();
                        if (c == null) {
                            subscriber.onComplete();
                            return;
                        }
                        demand.decrementAndGet();
                        subscriber.onNext(c);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    if (!cancelled.get()) subscriber.onError(e);
                }
            });
        };
    }
}
