package net.gaiadesk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.gaiadesk.Cancellation;
import org.jspecify.annotations.Nullable;

/** An engine over {@link HttpClient} (the hosted API; the LAN gateway with a pinned SSLContext). */
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
        CompletableFuture<HttpResponse<InputStream>> cf = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());
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
