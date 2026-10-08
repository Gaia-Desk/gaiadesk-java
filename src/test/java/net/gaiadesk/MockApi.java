package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import com.sun.net.httpserver.HttpsConfigurator;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.e2e.DeskSeal;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.e2e.SealedFrame;
import net.gaiadesk.e2e.SealedRequest;
import net.gaiadesk.internal.Json;
import net.gaiadesk.internal.Utf8Stream;

/**
 * A mock of the hosted GaiaDesk API AND the desks behind it, in this process (com.sun.net.httpserver). Each desk
 * may hold an X25519 key (listed as {@code e2e_pub} while online), opens sealed requests with it, runs the
 * operation (canned answers) and the "API" answers as the real one does: plaintext JSON / SSE / bytes for a
 * plaintext call; {@code {"e2e": {"events"}}}, the error envelope with a placeholder message and
 * {@code e2e.events}, {@code sealed} SSE events and NDJSON downloads for a sealed one. It also serves the hosted
 * routes (fleet, reach, wake, audit, webhooks, support), and can act as a desk's own local or LAN API. Every
 * request is recorded raw.
 */
final class MockApi implements AutoCloseable {
    static final String HTML_DESK = "999999990";
    static final String LIMITED_DESK = "999999991";
    static final String LIMITED_ONCE_DESK = "999999992";
    static final String FLAKY_DESK = "999999993";
    static final String OFFLINE_DESK = "000000001";
    static final String USAGE_DESK = "000000002";

    /** A desk behind the API. */
    static final class Desk {
        byte[] secret;
        List<byte[]> previous = new ArrayList<>();
        boolean online = true;
        boolean required;
        boolean wakeable;
        int hideKeyLookups;

        Desk secret(byte[] s) { secret = s; return this; }
        Desk online(boolean o) { online = o; return this; }
        Desk required(boolean r) { required = r; return this; }
        Desk wakeable(boolean w) { wakeable = w; return this; }
        Desk hideKeyLookups(int n) { hideKeyLookups = n; return this; }
    }

    /** One request as the API saw it. */
    static final class Recorded {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final byte[] body;

        Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        String body() { return new String(body, StandardCharsets.UTF_8); }

        JsonNode json() { return Json.parse(body()); }

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

        String raw() { return method + " " + path + " " + query + " " + headers + " " + body(); }
    }

    enum Mode { API, LOCAL, LAN }

    final Map<String, Desk> desks = new ConcurrentHashMap<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    final List<String> sealed = new CopyOnWriteArrayList<>();
    final List<String> plain = new CopyOnWriteArrayList<>();
    final List<String> wakes = new CopyOnWriteArrayList<>();
    final List<String> waits = new CopyOnWriteArrayList<>();
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, ObjectNode> webhooks = Collections.synchronizedMap(new LinkedHashMap<>());
    final Map<String, ObjectNode> sessions = Collections.synchronizedMap(new LinkedHashMap<>());
    final AtomicInteger connections = new AtomicInteger();
    volatile String tamper; // null, "flip", "plaintext"
    private final AtomicInteger flaky = new AtomicInteger();
    private final AtomicInteger limitedOnce = new AtomicInteger();
    private final AtomicInteger rid = new AtomicInteger();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-api");
        t.setDaemon(true);
        return t;
    });
    final Mode mode;
    final String url;

    MockApi() throws IOException {
        this(Mode.API, null);
    }

    MockApi(Mode mode, SSLContext tls) throws IOException {
        this.mode = mode;
        if (tls != null) {
            HttpsServer s = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setHttpsConfigurator(new HttpsConfigurator(tls));
            server = s;
        } else {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        server.createContext("/", ex -> {
            try {
                handle(ex);
            } catch (Throwable t) {
                try {
                    send(ex, 500, envelope("protocol", "mock API: " + t, null, null));
                } catch (Throwable ignored) {
                    // the answer had begun
                }
            } finally {
                ex.close();
            }
        });
        server.setExecutor(pool);
        server.start();
        url = (tls != null ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int port() { return server.getAddress().getPort(); }

    Recorded last() { return requests.get(requests.size() - 1); }

    List<Recorded> since(int n) { return new ArrayList<>(requests.subList(n, requests.size())); }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String requestId() {
        return String.format("req_%024x", rid.incrementAndGet());
    }

    ObjectNode envelope(String kind, String message, String reason, String desk) {
        ObjectNode env = Json.object();
        ObjectNode e = env.putObject("error");
        e.put("kind", kind);
        e.put("message", message);
        e.put("reason", reason != null ? reason : kind);
        if (desk != null) e.put("desk", desk);
        e.put("request_id", requestId());
        return env;
    }

    static int statusOf(String kind, String reason) {
        switch (kind) {
            case "usage": return 400;
            case "refused":
                if ("unauthenticated".equals(reason) || "admin_token_local_only".equals(reason)) return 401;
                if ("desk_busy".equals(reason) || "rate_limited".equals(reason)) return 429;
                return "e2e_required".equals(reason) ? 409 : 403;
            case "unreachable": return "timeout".equals(reason) ? 504 : "unknown_desk".equals(reason) ? 404 : 409;
            case "connection_lost":
            case "protocol": return 502;
            default: return 422;
        }
    }

    private void send(HttpExchange ex, int status, JsonNode body, String... headers) throws IOException {
        byte[] b = Bytes.utf8(Json.write(body));
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Request-Id", requestId());
        for (int i = 0; i + 1 < headers.length; i += 2) ex.getResponseHeaders().set(headers[i], headers[i + 1]);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void error(HttpExchange ex, String kind, String message, String reason, String desk, String... headers) throws IOException {
        send(ex, statusOf(kind, reason), envelope(kind, message, reason, desk), headers);
    }

    private static void tick() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? p : p.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    static ObjectNode obj(Object... kv) {
        ObjectNode o = Json.object();
        for (int i = 0; i + 1 < kv.length; i += 2) o.set((String) kv[i], Json.tree(kv[i + 1]));
        return o;
    }

    // ───────────────────────────── the desk ─────────────────────────────

    private static ObjectNode out(byte[] b) { return obj("event", "stdout", "data", Bytes.b64encode(b)); }

    private static ObjectNode exit(JsonNode result) {
        ObjectNode o = obj("event", "exit");
        o.set("result", result);
        return o;
    }

    private static ObjectNode err(String kind, String reason, String message) {
        ObjectNode o = obj("event", "error", "kind", kind, "message", message);
        if (reason != null) o.put("reason", reason);
        return o;
    }

    static ObjectNode job(String name, String state, Integer exitCode) {
        ObjectNode j = obj("name", name, "command", "make " + name, "state", state, "started_at_ms", 1791300000000L, "log_bytes", 0);
        if (exitCode != null) j.put("exit_code", exitCode);
        if (!state.equals("running")) j.put("ended_at_ms", 1791300290000L);
        else j.put("pid", 42);
        return j;
    }

    static ObjectNode token(String id, String label) {
        ObjectNode t = obj("label", label, "id", id, "issued_at_ms", 1791300000000L, "expires_at_ms", 1791904800000L, "low_priv", false, "revoked", false);
        t.putArray("scopes").add("exec").add("cp").add("jobs");
        return t;
    }

    /** What the desk does: its events for one operation (canned, deterministic). */
    private List<ObjectNode> run(String desk, JsonNode req, byte[] input) {
        List<ObjectNode> ev = new ArrayList<>();
        String op = req.path("op").asText();
        switch (op) {
            case "exec": {
                JsonNode spec = req.path("spec");
                String cmd = spec.path("command").isTextual() ? spec.get("command").asText() : join(spec.path("argv"));
                if (cmd.equals("refuse")) {
                    ev.add(err("refused", "token_refused", "this token (bot) has no exec scope on desk " + desk));
                    return ev;
                }
                if (cmd.equals("unreachable-cwd")) {
                    ev.add(exit(obj("desk", desk, "exit", 255, "remote_code", null, "duration_ms", 0, "notes", List.of(), "stdout", "", "stderr", "",
                            "timed_out", false, "truncated", false, "error", obj("kind", "failed", "reason", "no_such_cwd", "message", "no such directory"))));
                    return ev;
                }
                if (cmd.equals("as-admin")) {
                    // What the API answers an exec that asked for administrator work: refused before anything ran.
                    ev.add(exit(obj("desk", desk, "exit", 254, "remote_code", null, "duration_ms", 1, "notes", List.of(), "stdout", "", "stderr", "",
                            "timed_out", false, "truncated", false,
                            "error", obj("kind", "refused", "reason", "admin_not_via_api", "message", "administrator work is not available through the API"))));
                    return ev;
                }
                StringBuilder text = new StringBuilder("ran: " + cmd + " é\n");
                spec.path("env").fields().forEachRemaining(e -> text.append("env: ").append(e.getKey()).append('=').append(e.getValue().asText()).append('\n'));
                if (spec.path("stdin").isTextual()) text.append("stdin: ").append(spec.get("stdin").asText()).append('\n');
                int code = cmd.equals("fail") ? 3 : 0;
                ObjectNode result = obj("desk", desk, "exit", code, "remote_code", code, "duration_ms", 7, "notes", List.of(), "stdout", text.toString(),
                        "stderr", "warn\n", "timed_out", false, "truncated", false, "error", null, "mode", "pipes", "route", "the GaiaDesk server");
                if (spec.path("shell").isTextual()) result.put("shell", spec.get("shell").asText());
                if (!req.path("stream").asBoolean(false)) {
                    ev.add(exit(result));
                    return ev;
                }
                byte[] b = Bytes.utf8(text.toString());
                int cut = text.indexOf("é") + 1; // a byte inside é: the character split across two events
                ev.add(out(Arrays.copyOfRange(b, 0, cut)));
                ev.add(obj("event", "stderr", "data", Bytes.b64encode(Bytes.utf8("warn\n"))));
                ev.add(out(Arrays.copyOfRange(b, cut, b.length)));
                if (!cmd.equals("lose")) ev.add(exit(result));
                return ev;
            }
            case "job_start": {
                JsonNode spec = req.path("spec");
                ObjectNode j = job(spec.path("name").asText(), "running", null);
                j.put("command", join(spec.path("command")));
                ev.add(exit(j));
                return ev;
            }
            case "job_list": {
                ObjectNode r = Json.object();
                r.putArray("jobs").add(job("build", "running", null));
                ev.add(exit(r));
                return ev;
            }
            case "job_kill":
                ev.add(exit(job(req.path("name").asText(), "killed", null)));
                return ev;
            case "job_wait": {
                String name = req.path("name").asText();
                waits.add(req.path("timeout_ms").isNumber() ? String.valueOf(req.get("timeout_ms").asLong() / 1000) : "");
                if (name.equals("held-gone") || name.equals("nope")) {
                    ev.add(err("failed", null, "no job named \"" + name + "\""));
                } else if (name.equals("slow")) {
                    ev.add(exit(obj("job", job(name, "running", null), "timed_out", true)));
                } else {
                    ev.add(exit(obj("job", job(name, "exited", 3), "timed_out", false)));
                }
                return ev;
            }
            case "job_logs": {
                String name = req.path("name").asText();
                if (name.equals("missing")) {
                    ev.add(err("failed", null, "no job named \"missing\""));
                    return ev;
                }
                if (!req.path("follow").asBoolean(false)) {
                    ev.add(exit(obj("job", job(name, "running", null), "output", "tail " + (req.has("tail") ? req.get("tail").asText() : "all") + "\n")));
                    return ev;
                }
                byte[] line2 = Bytes.utf8("line2 é\n");
                ev.add(out(Bytes.utf8("line1\n")));
                ev.add(out(Arrays.copyOfRange(line2, 0, 7)));
                ev.add(out(Arrays.copyOfRange(line2, 7, line2.length)));
                ev.add(exit(obj("job", job(name, "exited", 0))));
                return ev;
            }
            case "stats":
                ev.add(exit(obj("desk", desk, "hostname", "studio", "os", "macos", "os_version", "26.1", "cpu_percent", 5, "cpus", 8,
                        "mem_total_mb", 16384, "mem_free_mb", 4096, "uptime_secs", 3600, "jobs_running", 1, "load", List.of(1.5, 1.2, 1.0),
                        "disks", List.of(Map.of("mount", "/", "total_mb", 500000, "free_mb", 100000)))));
                return ev;
            case "file_put": {
                String path = req.path("path").asText();
                files.put(path, input);
                ev.add(exit(copy("upload", desk, path, input.length)));
                return ev;
            }
            case "file_get": {
                String path = req.path("path").asText();
                if (path.equals("missing")) {
                    ev.add(err("failed", "not_found", "no such file: missing"));
                    return ev;
                }
                byte[] data = files.getOrDefault(path, Bytes.utf8("contents of " + path + "\n"));
                for (int i = 0; i < data.length; i += 48 * 1024) ev.add(out(Arrays.copyOfRange(data, i, Math.min(data.length, i + 48 * 1024))));
                ev.add(exit(copy("download", desk, path, data.length)));
                return ev;
            }
            case "token_mint": {
                ObjectNode r = Json.object();
                r.putArray("tokens").add(obj("desk", desk, "token", token("tok1", req.path("spec").path("name").asText()), "secret", "gdagt_minted_secret"));
                ev.add(exit(r));
                return ev;
            }
            case "token_list": {
                ObjectNode r = Json.object();
                r.putArray("tokens").add(token("tok1", "bot"));
                ev.add(exit(r));
                return ev;
            }
            case "token_revoke":
                ev.add(exit(obj("revoked", req.path("token").asText(), "stopped_sessions", 0)));
                return ev;
            default:
                ev.add(err("protocol", "unknown_op", "unknown operation"));
                return ev;
        }
    }

    private static ObjectNode copy(String direction, String desk, String path, long bytes) {
        return obj("direction", direction, "desk", desk, "destination", path, "files", 1, "dirs", 0, "bytes", bytes, "resumed_bytes", 0,
                "failed", List.of(), "seconds", 0);
    }

    private static String join(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (JsonNode n : arr) parts.add(n.asText());
        return String.join(" ", parts);
    }

    /** The plaintext SSE events of a desk's events (as the server maps them). */
    private static final class PlainMap {
        private final boolean logs;
        private final String desk;
        private final Utf8Stream outDec = new Utf8Stream();
        private final Utf8Stream errDec = new Utf8Stream();

        PlainMap(boolean logs, String desk) {
            this.logs = logs;
            this.desk = desk;
        }

        List<String[]> map(ObjectNode e) {
            List<String[]> v = new ArrayList<>();
            String event = e.path("event").asText();
            if (event.equals("stdout") || event.equals("stderr")) {
                boolean isErr = !logs && event.equals("stderr");
                String t = (isErr ? errDec : outDec).decode(Bytes.b64decode(e.get("data").asText()));
                if (t.isEmpty()) return v;
                String name = logs ? "output" : event;
                v.add(new String[] {name, Json.write(obj("event", name, "data", t))});
            } else if (event.equals("exit")) {
                JsonNode r = e.get("result");
                if (logs) {
                    String t = outDec.flush();
                    if (!t.isEmpty()) v.add(new String[] {"output", Json.write(obj("event", "output", "data", t))});
                    ObjectNode end = obj("event", "end");
                    end.set("job", r.get("job"));
                    v.add(new String[] {"end", Json.write(end)});
                } else {
                    String o = outDec.flush();
                    if (!o.isEmpty()) v.add(new String[] {"stdout", Json.write(obj("event", "stdout", "data", o))});
                    String x = errDec.flush();
                    if (!x.isEmpty()) v.add(new String[] {"stderr", Json.write(obj("event", "stderr", "data", x))});
                    ObjectNode rest = ((ObjectNode) r).deepCopy();
                    rest.remove(Arrays.asList("stdout", "stderr", "truncated"));
                    rest.put("event", "exit");
                    v.add(new String[] {"exit", Json.write(rest)});
                }
            } else if (event.equals("error")) {
                ObjectNode error = obj("kind", e.get("kind").asText(), "message", e.get("message").asText(), "desk", desk);
                if (e.has("reason")) error.put("reason", e.get("reason").asText());
                ObjectNode o = obj("event", "error");
                if (!logs) o.put("exit", e.get("kind").asText().equals("refused") ? 254 : 255);
                o.set("error", error);
                v.add(new String[] {"error", Json.write(o)});
            }
            return v;
        }
    }

    // ───────────────────────────── routing ─────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        connections.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ex.getRequestBody().transferTo(bo);
        Recorded rec = new Recorded(method, URLDecoder.decode(path, StandardCharsets.UTF_8), q, headers, bo.toByteArray());
        requests.add(rec);

        String auth = headers.getOrDefault("authorization", "");
        String deskToken = headers.get("x-gaiadesk-desk-token");
        String key = auth.startsWith("Bearer ") ? auth.substring(7) : "";
        if (mode != Mode.API) {
            if (key.startsWith("gdlocal_") && mode == Mode.LAN) {
                error(ex, "refused", "the desk's admin token works only on the desk itself; send an agent token in X-GaiaDesk-Desk-Token", "admin_token_local_only", null);
                return;
            }
            if (!key.isEmpty() && !key.startsWith("gdlocal_")) {
                error(ex, "refused", "not this desk's admin token", "unauthenticated", null);
                return;
            }
            if (!key.isEmpty()) key = "session-admin";
            else if (deskToken != null) key = "ak_desk-token";
        }
        if (key.isEmpty()) {
            error(ex, "refused", "Sign in, or send an API key as `Authorization: Bearer ak_…`.", "unauthenticated", null);
            return;
        }
        boolean isKey = key.startsWith("ak_");
        if (!path.startsWith("/v1/")) {
            error(ex, "unreachable", "no route", "no_such_route", null);
            return;
        }
        String route = path.substring(3);
        if (mode != Mode.API && !route.equals("/desks") && !route.matches("/desks/[^/]+/.+")) {
            error(ex, "unreachable", "not served by the desk", "no_such_route", null);
            return;
        }
        if (route.equals("/audit")) { audit(ex, q); return; }
        if (route.startsWith("/webhooks")) { webhooks(ex, rec, route); return; }
        if (route.startsWith("/support/sessions")) { support(ex, rec, route); return; }
        Matcher m = Pattern.compile("^/desks(?:/([^/]+)(/.*)?)?$").matcher(route);
        if (!m.matches()) {
            error(ex, "usage", "no route " + method + " " + path, "no_route", null);
            return;
        }
        if (m.group(1) == null) {
            fleet(ex);
            return;
        }
        String id = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        String rest = m.group(2) == null ? "" : m.group(2);
        if (id.equals(HTML_DESK)) {
            byte[] b = Bytes.utf8("<html><body>Internal Server Error</body></html>");
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(500, b.length);
            ex.getResponseBody().write(b);
            return;
        }
        if (id.equals(LIMITED_DESK) || (id.equals(LIMITED_ONCE_DESK) && limitedOnce.getAndIncrement() % 2 == 0)) {
            error(ex, "refused", "Too many requests for this key; try again in 7 s.", "rate_limited", null, "Retry-After", id.equals(LIMITED_DESK) ? "7" : "0");
            return;
        }
        if (id.equals(FLAKY_DESK) && flaky.getAndIncrement() % 2 == 0) {
            error(ex, "connection_lost", "The desk went away during this operation.", "desk_disconnected", id);
            return;
        }
        if (id.equals(USAGE_DESK) && !rest.isEmpty()) {
            error(ex, "usage", "bad request for this desk", "bad_body", id);
            return;
        }
        Desk d = desks.get(id);
        if (d == null && !id.equals(OFFLINE_DESK) && !id.equals(LIMITED_ONCE_DESK) && !id.equals(FLAKY_DESK) && !id.equals(USAGE_DESK)) {
            error(ex, "unreachable", "No desk with this id on your account or team.", "unknown_desk", id);
            return;
        }
        if (d == null) d = new Desk().online(!id.equals(OFFLINE_DESK));
        if (rest.isEmpty() && method.equals("GET")) {
            boolean stale = d.hideKeyLookups > 0 && d.hideKeyLookups-- > 0;
            ObjectNode o = deskRow(id, d);
            o.put("e2e_required", d.required && !stale);
            if (d.online && d.secret != null && !stale) o.put("e2e_pub", Bytes.b64url(E2eCrypto.x25519Public(d.secret)));
            else o.putNull("e2e_pub");
            o.set("wake", obj("doorbell_sockets", d.wakeable ? 1 : 0, "lan_wake", d.wakeable));
            send(ex, 200, o);
            return;
        }
        if (rest.equals("/reach") && method.equals("GET")) {
            ObjectNode r = obj("desk_id", id, "since", Long.parseLong(q.getOrDefault("since", "1790700000")));
            ArrayNode evs = r.putArray("events");
            evs.add(obj("at", 1791290000L, "online", false, "reason", "silent", "reason_text", "nothing heard from the host"));
            evs.add(obj("at", 1791200000L, "online", true, "reason", "registered", "reason_text", "connected", "version", "0.10.325"));
            send(ex, 200, r);
            return;
        }
        if (rest.equals("/wake") && method.equals("POST")) {
            wakes.add(id);
            if (!d.wakeable && !d.online) {
                error(ex, "unreachable", "Nothing can wake this desk now.", "no_wake_path", id);
                return;
            }
            boolean already = d.online;
            if (d.wakeable) d.online = true;
            send(ex, 200, obj("desk_id", id, "online", d.online, "woke", !already && d.online, "already_online", already,
                    "rang", obj("doorbell", already ? 0 : 1, "lan_helpers", 0), "waited_ms", 12), "Idempotent-Replayed", headers.containsKey("idempotency-key") ? "true" : "");
            return;
        }
        boolean tokens = rest.startsWith("/tokens");
        if (tokens && isKey) {
            error(ex, "refused", "token administration over the API works only for a signed-in person's own desk", "session_required", id);
            return;
        }
        if (tokens && method.equals("POST") && "admin-bot".equals(rec.json().path("name").asText())) {
            // What the API answers a mint asking for the admin scope.
            error(ex, "refused", "the admin scope cannot be minted through the API", "admin_not_via_api", id);
            return;
        }
        if (!tokens && isKey && deskToken == null) {
            error(ex, "refused", "from an API key, desk operations need a scoped agent token in X-GaiaDesk-Desk-Token", "desk_token_required", id);
            return;
        }
        deskOp(ex, rec, id, d, rest);
    }

    private ObjectNode deskRow(String id, Desk d) {
        ObjectNode o = obj("desk_id", id, "name", "desk-" + id, "online", d.online, "os", "macos", "app_version", "0.10.329", "owner", "you",
                "sources", List.of("account"));
        o.putArray("features").add("desk_op");
        if (d.secret != null) ((ArrayNode) o.get("features")).add("desk_op_e2e");
        if (!d.online) {
            o.put("offline_since", 1791290000L);
            o.put("offline_reason", "silent");
            o.put("offline_reason_text", "nothing heard from the host (asleep, offline or a dead connection)");
        }
        return o;
    }

    private void fleet(HttpExchange ex) throws IOException {
        ObjectNode r = Json.object();
        ArrayNode list = r.putArray("devices");
        desks.forEach((id, d) -> list.add(deskRow(id, d)));
        r.putArray("sources").add("server");
        r.putArray("notes");
        r.set("identity", obj("account", "you@example.com", "source", "api_key"));
        send(ex, 200, r);
    }

    private void audit(HttpExchange ex, Map<String, String> q) throws IOException {
        int limit = Integer.parseInt(q.getOrDefault("limit", "100"));
        long until = Long.parseLong(q.getOrDefault("until_ms", String.valueOf(Long.MAX_VALUE)));
        ObjectNode r = Json.object();
        ArrayNode events = r.putArray("events");
        // 250 events, one per millisecond pair (every two share a time: paging must not lose or repeat them).
        for (int i = 0; i < 250 && events.size() < limit; i++) {
            long at = 1791300000000L - (i / 2);
            if (at > until) continue;
            events.add(obj("id", "evt_" + i, "action", "api.execOnDesk", "stream", "api", "occurred_at_ms", at,
                    "actor", obj("type", "api_key", "id", "ak_1"), "target", obj("type", "desk", "id", "123456789", "name", "studio"),
                    "metadata", obj("result", "ok")));
        }
        send(ex, 200, r);
    }

    private void webhooks(HttpExchange ex, Recorded rec, String route) throws IOException {
        if (route.equals("/webhooks") && rec.method.equals("GET")) {
            ObjectNode r = Json.object();
            ArrayNode list = r.putArray("webhooks");
            synchronized (webhooks) {
                webhooks.values().forEach(w -> list.add(w.deepCopy()));
            }
            send(ex, 200, r);
            return;
        }
        if (route.equals("/webhooks") && rec.method.equals("POST")) {
            JsonNode b = rec.json();
            if (!b.path("url").asText().startsWith("https://")) {
                error(ex, "usage", "the url must be https://", "bad_url", null);
                return;
            }
            String id = String.format("wh_%016x", webhooks.size() + 1);
            ObjectNode w = obj("id", id, "url", b.get("url").asText(), "events", b.get("events"), "description", b.path("description").asText(""), "created_at", 1791300000L);
            webhooks.put(id, w);
            ObjectNode created = w.deepCopy();
            created.put("secret", "whsec_" + "0".repeat(64));
            send(ex, 201, created);
            return;
        }
        Matcher m = Pattern.compile("^/webhooks/([^/]+)$").matcher(route);
        if (m.matches() && rec.method.equals("DELETE")) {
            if (webhooks.remove(m.group(1)) == null) {
                error(ex, "unreachable", "no such webhook", "unknown_webhook", null);
                return;
            }
            send(ex, 200, obj("deleted", m.group(1)));
            return;
        }
        error(ex, "usage", "no route", "no_route", null);
    }

    private ObjectNode session(String id, JsonNode b) {
        ObjectNode s = obj("id", id, "state", "waiting", "mode", b.path("mode").asText("view"), "customer_present", false, "customer_verified", true,
                "join_code", "123456789", "join_url", "https://gaiadesk.net/app/support.html#session=" + id, "owner", "you@example.com",
                "created_at", 1791300000L, "expires_at", 1791300000L + b.path("expires_in").asLong(3600), "desk_id", null,
                "origin", b.has("origin") ? b.get("origin").asText() : null);
        s.set("customer", b.has("customer") ? b.get("customer") : Json.object());
        return s;
    }

    private void support(HttpExchange ex, Recorded rec, String route) throws IOException {
        if (route.equals("/support/sessions") && rec.method.equals("POST")) {
            String id = String.format("ss_%016x", sessions.size() + 1);
            ObjectNode s = session(id, rec.body.length == 0 ? Json.object() : rec.json());
            sessions.put(id, s);
            ObjectNode created = s.deepCopy();
            created.put("embed_token", "gdemb_" + "a".repeat(64));
            send(ex, 201, created);
            return;
        }
        if (route.equals("/support/sessions") && rec.method.equals("GET")) {
            ObjectNode r = Json.object();
            ArrayNode list = r.putArray("sessions");
            synchronized (sessions) {
                sessions.values().forEach(s -> list.add(s.deepCopy()));
            }
            send(ex, 200, r);
            return;
        }
        Matcher m = Pattern.compile("^/support/sessions/([^/]+)$").matcher(route);
        if (m.matches() && rec.method.equals("GET")) {
            ObjectNode s = sessions.get(m.group(1));
            if (s == null) {
                error(ex, "unreachable", "no such support session", "unknown_support_session", null);
                return;
            }
            send(ex, 200, s);
            return;
        }
        error(ex, "usage", "no route", "no_route", null);
    }

    /** The route's operation and its plaintext request. */
    private static String[] routeOp(String method, String rest, Map<String, String> q) {
        if (rest.equals("/exec") && method.equals("POST")) return new String[] {"exec", "1".equals(q.get("stream")) ? "exec" : null};
        if (rest.equals("/jobs") && method.equals("POST")) return new String[] {"job_start", null};
        if (rest.equals("/jobs") && method.equals("GET")) return new String[] {"job_list", null};
        if (rest.equals("/stats") && method.equals("GET")) return new String[] {"stats", null};
        if (rest.equals("/files") && method.equals("PUT")) return new String[] {"file_put", null};
        if (rest.equals("/files") && method.equals("GET")) return new String[] {"file_get", null};
        if (rest.equals("/tokens") && method.equals("POST")) return new String[] {"token_mint", null};
        if (rest.equals("/tokens") && method.equals("GET")) return new String[] {"token_list", null};
        if (rest.matches("/tokens/[^/]+") && method.equals("DELETE")) return new String[] {"token_revoke", null};
        if (rest.matches("/jobs/[^/]+") && method.equals("DELETE")) return new String[] {"job_kill", null};
        if (rest.matches("/jobs/[^/]+/wait") && method.equals("GET")) return new String[] {"job_wait", null};
        if (rest.matches("/jobs/[^/]+/logs") && method.equals("GET")) return new String[] {"job_logs", "1".equals(q.get("follow")) ? "logs" : null};
        return null;
    }

    private static ObjectNode plainRequest(String op, String rest, Map<String, String> q, byte[] body) {
        ObjectNode r = obj("op", op);
        JsonNode json = body.length > 0 && !op.equals("file_put") ? Json.parse(new String(body, StandardCharsets.UTF_8)) : Json.object();
        String[] parts = rest.split("/");
        switch (op) {
            case "exec":
                r.set("spec", json);
                if ("1".equals(q.get("stream"))) r.put("stream", true);
                break;
            case "job_start":
            case "token_mint":
                r.set("spec", json);
                break;
            case "file_put":
                r.put("path", q.get("path"));
                r.put("size", body.length);
                break;
            case "file_get":
                r.put("path", q.get("path"));
                break;
            case "token_revoke":
                r.put("token", URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
                break;
            case "job_kill":
                r.put("name", URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
                break;
            case "job_wait":
                r.put("name", URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
                if (q.containsKey("timeout")) r.put("timeout_ms", Long.parseLong(q.get("timeout")) * 1000);
                break;
            case "job_logs":
                r.put("name", URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
                if (q.containsKey("tail")) r.put("tail", Long.parseLong(q.get("tail")));
                if ("1".equals(q.get("follow"))) r.put("follow", true);
                break;
            default:
                break;
        }
        return r;
    }

    private void deskOp(HttpExchange ex, Recorded rec, String id, Desk d, String rest) throws IOException {
        SealedRequest sealedReq = null;
        if (rec.method.equals("POST") && rec.body.length > 0) {
            JsonNode j = rec.json();
            if (j.has("e2e")) sealedReq = SealedRequest.fromJson(j.get("e2e"));
        }
        String h = rec.header("gaiadesk-e2e");
        if (h != null) sealedReq = SealedRequest.fromJson(Json.parse(new String(Bytes.b64decode(h), StandardCharsets.UTF_8)));
        String[] route = routeOp(rec.method, rest, rec.query);
        if (route == null) {
            error(ex, "usage", "no route", "no_route", id);
            return;
        }
        String op = route[0];
        boolean logs = "logs".equals(route[1]);
        boolean stream = route[1] != null;
        if (!d.online && sealedReq == null && id.equals(OFFLINE_DESK)) {
            error(ex, "unreachable", "The desk is offline: nothing heard from the host.", "offline", id);
            return;
        }
        if (!d.online && sealedReq == null) d.online = true; // the API wakes it for the operation
        JsonNode req;
        DeskSeal seal = null;
        byte[] input = rec.body;
        if (sealedReq != null) {
            if (d.secret == null) {
                error(ex, "protocol", "the desk cannot open end-to-end encrypted operations", "e2e_unsupported", id);
                return;
            }
            DeskSeal opened = null;
            List<byte[]> keys = new ArrayList<>();
            keys.add(d.secret);
            keys.addAll(d.previous);
            for (byte[] k : keys) {
                try {
                    opened = DeskSeal.open(k, id, op, sealedReq);
                    break;
                } catch (RuntimeException e) {
                    opened = null;
                }
            }
            if (opened == null) {
                error(ex, "refused", "the end-to-end encrypted request did not open: it was altered, or sealed to another key", "e2e_decrypt_failed", id);
                return;
            }
            JsonNode inner = Json.parse(new String(opened.plain(), StandardCharsets.UTF_8));
            if (inner.path("v").asInt() != 1 || Math.abs(inner.path("ts").asLong() - System.currentTimeMillis() / 1000) > 600) {
                error(ex, "refused", "stale", "e2e_stale", id);
                return;
            }
            if (!inner.path("request").path("op").asText().equals(op)) {
                error(ex, "refused", "op mismatch", "e2e_op_mismatch", id);
                return;
            }
            req = inner.get("request");
            seal = opened;
            if (op.equals("file_put")) {
                ByteArrayOutputStream parts = new ByteArrayOutputStream();
                boolean lastFrame = false;
                for (String line : rec.body().split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    DeskSeal.Input in = seal.openInput(SealedFrame.fromJson(Json.parse(line)));
                    parts.write(in.data);
                    lastFrame = in.last;
                }
                if (!lastFrame) {
                    error(ex, "usage", "the upload ended early", "body_interrupted", id);
                    return;
                }
                input = parts.toByteArray();
            }
            sealed.add(op);
        } else {
            if (d.required) {
                error(ex, "refused", "This desk requires end-to-end encryption for API commands.", "e2e_required", id);
                return;
            }
            req = plainRequest(op, rest, rec.query, rec.body);
            plain.add(op);
        }
        List<ObjectNode> events = run(id, req, input);
        ObjectNode fin = events.get(events.size() - 1);
        ObjectNode failed = fin.path("event").asText().equals("error") ? fin : null;
        DeskSeal s = seal;

        if (stream) {
            if (events.get(0).path("event").asText().equals("error")) {
                ObjectNode e0 = events.get(0);
                send(ex, statusOf(e0.get("kind").asText(), e0.path("reason").asText(null)), errorAnswer(id, events, e0, s));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("X-Request-Id", requestId());
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            PlainMap map = new PlainMap(logs, id);
            for (ObjectNode e : events) {
                if (s != null && !"plaintext".equals(tamper)) {
                    ObjectNode f = sealOne(s, e).toJson();
                    ObjectNode ev = obj("event", "sealed");
                    ev.setAll(f);
                    sse(os, "sealed", Json.write(ev));
                } else {
                    for (String[] x : map.map(e)) sse(os, x[0], x[1]);
                }
            }
            String last = fin.path("event").asText();
            if (!last.equals("exit") && !last.equals("error")) {
                ObjectNode lost = obj("event", "error");
                if (!logs) lost.put("exit", 255);
                lost.set("error", obj("kind", "connection_lost", "message", "The desk went away during this operation.", "desk", id, "reason", "desk_disconnected"));
                sse(os, "error", Json.write(lost));
            }
            os.close();
            return;
        }

        if (op.equals("file_get")) {
            if (events.get(0).path("event").asText().equals("error")) {
                ObjectNode e0 = events.get(0);
                send(ex, statusOf(e0.get("kind").asText(), e0.path("reason").asText(null)), errorAnswer(id, events, e0, s));
                return;
            }
            ex.getResponseHeaders().set("X-Request-Id", requestId());
            if (s == null) {
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    for (ObjectNode e : events) if (e.get("event").asText().equals("stdout")) os.write(Bytes.b64decode(e.get("data").asText()));
                }
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, 0);
            List<ObjectNode> keep = req.path("path").asText().equals("truncated") ? events.subList(0, events.size() - 1) : events;
            try (OutputStream os = ex.getResponseBody()) {
                for (ObjectNode e : keep) os.write(Bytes.utf8(Json.write(sealOne(s, e).toJson()) + "\n"));
            }
            return;
        }

        int okStatus = op.equals("job_start") || op.equals("token_mint") ? 201 : 200;
        String name = req.path("name").asText("");
        boolean held = op.equals("job_wait") && (name.equals("held") || name.equals("held-gone") || name.equals("held-fail"));
        if (held) {
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("X-Request-Id", requestId());
            ex.getResponseHeaders().set("GaiaDesk-Held", "1");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 3; i++) {
                    os.write(' ');
                    os.flush();
                    tick();
                }
                JsonNode body;
                if (name.equals("held-fail")) {
                    ObjectNode env = envelope("connection_lost", "The desk went away during this operation.", "desk_disconnected", id);
                    ((ObjectNode) env.get("error")).put("status", 502);
                    body = env;
                } else if (failed != null) {
                    ObjectNode env = errorAnswer(id, events, failed, s);
                    ((ObjectNode) env.get("error")).put("status", statusOf(failed.get("kind").asText(), failed.path("reason").asText(null)));
                    body = env;
                } else {
                    body = s != null ? sealedAnswer(s, events) : fin.get("result");
                }
                os.write(Bytes.utf8(Json.write(body)));
            }
            return;
        }
        if (name.equals("sleepy")) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failed != null) {
            send(ex, statusOf(failed.get("kind").asText(), failed.path("reason").asText(null)), errorAnswer(id, events, failed, s));
            return;
        }
        if (s != null && !"plaintext".equals(tamper)) {
            send(ex, okStatus, sealedAnswer(s, events));
            return;
        }
        send(ex, okStatus, fin.get("result"));
    }

    private SealedFrame sealOne(DeskSeal s, ObjectNode e) {
        SealedFrame f = s.sealEvent(e);
        if ("flip".equals(tamper)) {
            byte[] c = Bytes.b64decode(f.getCiphertext());
            c[0] ^= 1;
            f = new SealedFrame(f.getSeq(), f.getNonce(), Bytes.b64url(c));
        }
        return f;
    }

    private ObjectNode sealedAnswer(DeskSeal s, List<ObjectNode> events) {
        ObjectNode a = Json.object();
        ObjectNode e2e = a.putObject("e2e");
        e2e.put("v", 1);
        ArrayNode arr = e2e.putArray("events");
        for (ObjectNode e : events) arr.add(sealOne(s, e).toJson());
        return a;
    }

    private ObjectNode errorAnswer(String desk, List<ObjectNode> events, ObjectNode e, DeskSeal s) {
        String kind = e.get("kind").asText();
        String k = Arrays.asList("refused", "usage", "protocol", "unreachable", "connection_lost").contains(kind) ? kind : "failed";
        ObjectNode env = envelope(k, s != null ? "The desk reported an error (end-to-end encrypted)." : e.get("message").asText(), e.path("reason").asText(null), desk);
        if (s != null) {
            ObjectNode e2e = env.putObject("e2e");
            e2e.put("v", 1);
            ArrayNode arr = e2e.putArray("events");
            for (ObjectNode x : events) arr.add(sealOne(s, x).toJson());
        }
        return env;
    }

    /** One SSE event written in pieces (split mid-line, CRLF across writes), a keep-alive comment first. */
    private static void sse(OutputStream os, String name, String data) throws IOException {
        byte[] text = Bytes.utf8(": keep-alive\r\nevent: " + name + "\r\ndata: " + data + "\r\n\r\n");
        int[] cuts = {3, text.length / 2, text.length - 1, text.length};
        int at = 0;
        for (int c : cuts) {
            os.write(text, at, c - at);
            os.flush();
            at = c;
            tick();
        }
    }
}
