package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.e2e.CallerSeal;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.e2e.SealedOperation;
import net.gaiadesk.internal.E2eAnswers;
import net.gaiadesk.internal.E2eLayer;
import net.gaiadesk.internal.Errors;
import net.gaiadesk.internal.HttpCall;
import net.gaiadesk.internal.HttpEngine;
import net.gaiadesk.internal.HttpResult;
import net.gaiadesk.internal.IdleTimeoutInputStream;
import net.gaiadesk.internal.Json;
import net.gaiadesk.internal.Urls;
import org.jspecify.annotations.Nullable;

/**
 * One /v1 API, however it is reached: requests, credentials, retries, end-to-end sealing, and every failure as
 * its typed error. The same operation code runs over the hosted API, the desk's local API and a LAN gateway.
 */
final class Core {
    /** The credential headers for one request ({@code callToken}: the call's own desk token, when given). */
    interface Credentials {
        Map<String, String> headers(@Nullable String callToken);
    }

    /** The query parameters a sealed request carries inside instead. */
    private static final List<String> SEALED_QUERY = Arrays.asList("path", "tail", "timeout");
    private static final String USER_AGENT = "gaiadesk-java/" + GaiaDesk.VERSION + " (Java " + System.getProperty("java.version") + ")";

    final TransportKind transport;
    final String baseUrl;
    final String where;
    private final HttpEngine engine;
    private final Credentials credentials;
    final @Nullable E2eLayer e2e;
    final RetryPolicy retry;
    final Timeouts timeouts;
    final Executor executor;

    Core(TransportKind transport, String baseUrl, String where, HttpEngine engine, Credentials credentials, @Nullable E2eConfig e2eConfig,
            RetryPolicy retry, Timeouts timeouts, Executor executor) {
        this.transport = transport;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.where = where;
        this.engine = engine;
        this.credentials = credentials;
        this.retry = retry;
        this.timeouts = timeouts;
        this.executor = executor;
        this.e2e = e2eConfig == null ? null
                : new E2eLayer(e2eConfig.mode, e2eConfig.pins, e2eConfig.warn, (method, path, deskToken, json, cancel) -> {
                    Req r = new Req(method, path, cancel);
                    r.deskToken = deskToken;
                    r.json = json;
                    return json(r);
                }, this.baseUrl);
    }

    /** What the e2e layer is made from (the hosted API only). */
    static final class E2eConfig {
        final E2eMode mode;
        final Map<String, byte[]> pins;
        final java.util.function.Consumer<String> warn;

        E2eConfig(E2eMode mode, Map<String, byte[]> pins, java.util.function.Consumer<String> warn) {
            this.mode = mode;
            this.pins = pins;
            this.warn = warn;
        }
    }

    /** One request: its route, body, per-call options and (a desk operation) what it is when sealed. */
    static final class Req {
        final String method;
        final String path;
        final Cancellation cancel;
        final Map<String, String> query = new LinkedHashMap<>();
        @Nullable JsonNode json;
        byte @Nullable [] bytes;
        String accept = "application/json";
        @Nullable String deskToken;
        @Nullable Integer wake;
        @Nullable Duration timeout;
        @Nullable String idempotencyKey;
        @Nullable String e2eDesk;
        @Nullable String e2eOp;
        @Nullable ObjectNode e2eRequest;

        Req(String method, String path, Cancellation cancel) {
            this.method = method;
            this.path = path;
            this.cancel = cancel;
        }

        String op() {
            return method + " " + path;
        }

        Req query(String k, @Nullable Object v) {
            if (v != null) query.put(k, String.valueOf(v));
            return this;
        }

        /** This is desk operation {@code op} on {@code desk}, sealed as {@code request} ({@code {"op": …}}). */
        Req sealedAs(String desk, String op, ObjectNode request) {
            this.e2eDesk = desk;
            this.e2eOp = op;
            this.e2eRequest = request;
            return this;
        }
    }

    /** A response with a 2xx status, and the seal its body opens with when the call was sealed. */
    static final class Res implements AutoCloseable {
        final HttpResult http;
        final @Nullable CallerSeal seal;
        final String op;

        Res(HttpResult http, @Nullable CallerSeal seal, String op) {
            this.http = http;
            this.seal = seal;
            this.op = op;
        }

        @Override
        public void close() {
            http.close();
        }
    }

    /** The UsageException for an operation this transport does not serve. */
    UsageException notServed(String what, @Nullable String hint) {
        String h = hint != null ? hint : "use the CLI or native transport";
        return new UsageException(what + " is not available over the " + transport.label() + " transport; " + h,
                ErrorDetails.builder().kind("usage").argv(List.of(what)).build());
    }

    /** A hosted-only route: refused before anything is sent on the local and LAN transports. */
    void hostedOnly(String what) {
        if (transport != TransportKind.API) throw notServed(what, "the desk serves desk operations only; use the hosted API (GaiaDesk.builder().apiKey(...))");
    }

    // ───────────────────────────── requests ─────────────────────────────

    /** A request answered with JSON (a sealed answer opened). */
    JsonNode json(Req r) {
        Res res = request(r);
        Cancellation.Registration reg = r.cancel.onCancel(res::close);
        String text;
        try {
            text = new String(readAll(res.http.body()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (r.cancel.isCancelled()) throw Errors.interrupted(r.op());
            throw new ConnectionLostException("the answer to " + r.op() + " was cut off: " + e.getMessage(),
                    ErrorDetails.builder().kind("connection_lost").exitCode(255).argv(List.of(r.op())).build(), e);
        } finally {
            reg.close();
            res.close();
        }
        JsonNode json = Json.tryParse(text);
        if (json == null) {
            throw new ProtocolException("the GaiaDesk API answered " + r.op() + " with something that is not JSON",
                    ErrorDetails.builder().kind("protocol").exitCode(255).argv(List.of(r.op())).status(res.http.status())
                            .requestId(res.http.header("x-request-id")).build());
        }
        return res.seal != null ? E2eAnswers.openAnswer(json, res.seal, r.op()) : json;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * One operation: sent (sealed end to end when the desk can open it), retried as the retry policy allows;
     * a failure is the typed error from its envelope.
     */
    Res request(Req r) {
        for (int attempt = 0; ; attempt++) {
            try {
                return once(r);
            } catch (GaiaDeskException e) {
                if (r.cancel.isCancelled()) throw e;
                long delay = retryDelay(e, r, attempt);
                if (delay < 0) throw e;
                sleep(delay, r);
            }
        }
    }

    private Res once(Req r) {
        E2eLayer layer = e2e;
        if (layer == null || r.e2eDesk == null || r.e2eOp == null || r.e2eRequest == null) return send(r, null);
        return layer.call(r.e2eDesk, r.e2eOp, r.e2eRequest, r.deskToken, r.wake, r.cancel, sealed -> send(r, sealed));
    }

    /** The wait before retrying {@code r} after {@code e} (the rule is {@link RetryPolicy}'s); -1: not retried. */
    private long retryDelay(GaiaDeskException e, Req r, int attempt) {
        if (!RetryPolicy.retryable(e, r.method)) return -1;
        return retry.delayMillis(attempt, RetryPolicy.honouredRetryAfter(e));
    }

    private static void sleep(long ms, Req r) {
        if (ms <= 0) return;
        Object lock = new Object();
        Cancellation.Registration reg = r.cancel.onCancel(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        try {
            long until = System.currentTimeMillis() + ms;
            synchronized (lock) {
                for (long left = ms; left > 0 && !r.cancel.isCancelled(); left = until - System.currentTimeMillis()) lock.wait(left);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw Errors.interrupted(r.op());
        } finally {
            reg.close();
        }
        if (r.cancel.isCancelled()) throw Errors.interrupted(r.op());
    }

    /** A duration in seconds as people write it: {@code 1}, {@code 0.5}, {@code 960}. */
    static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0);
    }

    /** The answer, every read of its body bounded by the idle timeout. */
    private HttpResult bounded(HttpResult raw, String op) {
        Duration idle = timeouts.getIdleTimeout();
        if (idle == null) return raw;
        InputStream body = new IdleTimeoutInputStream(raw.body(), idle, raw::close, () -> new ConnectionLostException(
                where + " stopped sending its answer to " + op + ": nothing for " + seconds(idle) + " s (idleTimeout)",
                ErrorDetails.builder().kind("timeout").reason("timeout").exitCode(255).argv(List.of(op)).build()));
        return new HttpResult() {
            @Override
            public int status() {
                return raw.status();
            }

            @Override
            public @Nullable String header(String name) {
                return raw.header(name);
            }

            @Override
            public InputStream body() {
                return body;
            }

            @Override
            public void close() {
                raw.close(); // abandoned (never reused): what is left of the body is not read
            }
        };
    }

    private String url(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);
        char sep = '?';
        for (Map.Entry<String, String> q : query.entrySet()) {
            sb.append(sep).append(Urls.encode(q.getKey())).append('=').append(Urls.encode(q.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    private Res send(Req r, @Nullable SealedOperation sealed) {
        String op = r.op();
        Map<String, String> query = new LinkedHashMap<>(r.query);
        if (r.wake != null) query.put("wake_s", String.valueOf(r.wake));
        Map<String, String> headers = new LinkedHashMap<>(credentials.headers(r.deskToken));
        headers.put("Accept", r.accept);
        headers.put("User-Agent", USER_AGENT);
        if (r.idempotencyKey != null && r.method.equals("POST")) headers.put("Idempotency-Key", r.idempotencyKey);
        byte[] body = null;
        if (sealed != null) {
            for (String k : SEALED_QUERY) query.remove(k);
            if (r.method.equals("POST")) {
                ObjectNode b = Json.object();
                b.set("e2e", sealed.getRequest().toJson());
                headers.put("Content-Type", "application/json");
                body = Bytes.utf8(Json.write(b));
            } else {
                headers.put(E2eCrypto.HEADER, E2eCrypto.requestHeader(sealed.getRequest()));
                if (r.bytes != null) {
                    headers.put("Content-Type", E2eCrypto.FRAMES_CONTENT_TYPE);
                    body = E2eAnswers.sealUpload(sealed.getSeal(), r.bytes);
                }
            }
        } else if (r.json != null) {
            headers.put("Content-Type", "application/json");
            body = Bytes.utf8(Json.write(r.json));
        } else if (r.bytes != null) {
            headers.put("Content-Type", "application/octet-stream");
            body = r.bytes;
        }
        if (r.cancel.isCancelled() || Thread.currentThread().isInterrupted()) throw Errors.interrupted(op);
        // The answer must begin within the response timeout (sending the request included); its body is then read
        // under the idle timeout (bounded()), so a peer that goes silent is an error, never a hang.
        Duration timeout = r.timeout != null ? r.timeout : timeouts.getResponseTimeout();
        HttpResult res;
        try {
            res = bounded(engine.send(new HttpCall(r.method, URI.create(url(r.path, query)), headers, body, timeout), r.cancel), op);
        } catch (GaiaDeskException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Errors.interrupted(op);
        } catch (IOException | RuntimeException e) {
            if (r.cancel.isCancelled()) throw Errors.interrupted(op);
            if (e instanceof HttpConnectTimeoutException) {
                throw new UnreachableException(where + " could not be reached: connecting timed out (connectTimeout)",
                        ErrorDetails.builder().kind("timeout").reason("timeout").exitCode(255).argv(List.of(op)).build(), e);
            }
            if (e instanceof HttpTimeoutException) {
                String which = timeout == null ? "in time" : "within " + seconds(timeout) + " s (" + (r.timeout != null ? "requestTimeout" : "responseTimeout") + ")";
                throw new UnreachableException(where + " did not answer " + op + " " + which,
                        ErrorDetails.builder().kind("timeout").reason("timeout").exitCode(255).argv(List.of(op)).build(), e);
            }
            throw new UnreachableException(where + " could not be reached: " + (e.getMessage() != null ? e.getMessage() : e.toString()),
                    ErrorDetails.builder().kind("network").reason("network").exitCode(255).argv(List.of(op)).build(), e);
        }
        int status = res.status();
        if (status < 200 || status > 299) {
            String text;
            try {
                text = new String(readAll(res.body()), StandardCharsets.UTF_8);
            } catch (IOException | ConnectionLostException e) {
                text = "";
            } finally {
                res.close();
            }
            CallerSeal seal = sealed != null ? sealed.getSeal() : null;
            throw Errors.apiError(status, res.header("x-request-id"), res.header("retry-after"), text, op,
                    seal == null ? null : json -> E2eAnswers.openErrorEnvelope(json, seal, op));
        }
        return new Res(res, sealed != null ? sealed.getSeal() : null, op);
    }
}
