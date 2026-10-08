package net.gaiadesk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.gaiadesk.Cancellation;
import org.jspecify.annotations.Nullable;

/**
 * An engine over {@link HttpClient} (the hosted API; the LAN gateway with a pinned SSLContext). The request's
 * {@link HttpRequest.Builder#timeout timeout} bounds the whole exchange up to the response's headers (connecting
 * and sending the body included); the body is read through {@link BodyStream}, which a close from another thread
 * (an idle timeout, a cancellation) unblocks on every JDK.
 */
public final class JdkHttpEngine implements HttpEngine {
    private final HttpClient client;

    public JdkHttpEngine(HttpClient client) {
        this.client = client;
    }

    @Override
    public HttpResult send(HttpCall call, Cancellation cancel) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(call.uri)
                .method(call.method, call.body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(call.body));
        for (Map.Entry<String, String> h : call.headers.entrySet()) b.header(h.getKey(), h.getValue());
        if (call.timeout != null) b.timeout(call.timeout);
        CompletableFuture<HttpResponse<InputStream>> cf = client.sendAsync(b.build(), info -> new BodyStream());
        HttpResponse<InputStream> res;
        Cancellation.Registration reg = cancel.onCancel(() -> cf.cancel(true));
        try {
            res = cf.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c == null ? "the request failed" : c.toString(), c);
        } catch (CancellationException e) {
            throw new InterruptedIOException("cancelled");
        } catch (InterruptedException e) {
            cf.cancel(true);
            throw e;
        } finally {
            reg.close();
        }
        return new Result(res);
    }

    /**
     * A response body as an InputStream, fed by the client one buffer list at a time. {@link #close()} cancels the
     * subscription (the connection is closed, never reused) and wakes a read blocked on another thread, which then
     * throws. ({@code BodyHandlers.ofInputStream()}'s close does not wake a blocked read on every JDK.)
     */
    static final class BodyStream extends InputStream implements HttpResponse.BodySubscriber<InputStream> {
        private static final List<ByteBuffer> END = new ArrayList<>();

        private final LinkedBlockingQueue<List<ByteBuffer>> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<InputStream> body = CompletableFuture.completedFuture(this);
        private volatile Flow.@Nullable Subscription subscription;
        private volatile @Nullable Throwable error;
        private volatile boolean closed;
        private @Nullable Iterator<ByteBuffer> current;
        private @Nullable ByteBuffer buffer;
        private boolean ended;

        @Override
        public CompletionStage<InputStream> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (closed) s.cancel();
            else s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            queue.add(item);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            queue.add(END);
        }

        @Override
        public void onComplete() {
            queue.add(END);
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
            if (len == 0) return 0;
            for (;;) {
                if (closed) throw new IOException("the response body was closed");
                ByteBuffer buf = buffer;
                if (buf != null && buf.hasRemaining()) {
                    int n = Math.min(len, buf.remaining());
                    buf.get(b, off, n);
                    return n;
                }
                Iterator<ByteBuffer> it = current;
                if (it != null && it.hasNext()) {
                    buffer = it.next();
                    continue;
                }
                if (ended) return -1;
                if (it != null) {
                    current = null;
                    Flow.Subscription s = subscription;
                    if (s != null) s.request(1);
                }
                List<ByteBuffer> next;
                try {
                    next = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted reading the response body");
                }
                if (next == END) {
                    if (closed) throw new IOException("the response body was closed");
                    Throwable t = error;
                    if (t != null) throw t instanceof IOException ? (IOException) t : new IOException(t.toString(), t);
                    ended = true;
                    return -1;
                }
                current = next.iterator();
            }
        }

        @Override
        public int available() {
            ByteBuffer buf = buffer;
            return buf == null ? 0 : buf.remaining();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Flow.Subscription s = subscription;
            if (s != null) s.cancel();
            queue.add(END);
        }
    }

    private static final class Result implements HttpResult {
        private final HttpResponse<InputStream> res;

        Result(HttpResponse<InputStream> res) {
            this.res = res;
        }

        @Override
        public int status() {
            return res.statusCode();
        }

        @Override
        public @Nullable String header(String name) {
            return res.headers().firstValue(name).orElse(null);
        }

        @Override
        public InputStream body() {
            return res.body();
        }

        @Override
        public void close() {
            try {
                res.body().close();
            } catch (IOException ignored) {
                // Abandoning it.
            }
        }
    }
}
