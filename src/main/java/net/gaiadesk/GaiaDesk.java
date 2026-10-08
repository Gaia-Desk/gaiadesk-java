package net.gaiadesk;

import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.internal.Check;
import net.gaiadesk.internal.HttpEngine;
import net.gaiadesk.internal.JdkHttpEngine;
import net.gaiadesk.internal.PinnedTrust;
import net.gaiadesk.internal.Threads;
import net.gaiadesk.model.AuditEvent;
import net.gaiadesk.model.CopyResult;
import net.gaiadesk.model.DeskDetail;
import net.gaiadesk.model.DeskStats;
import net.gaiadesk.model.DeviceList;
import net.gaiadesk.model.ExecResult;
import net.gaiadesk.model.Job;
import net.gaiadesk.model.JobWaitResult;
import net.gaiadesk.model.MintResult;
import net.gaiadesk.model.ReachLog;
import net.gaiadesk.model.SupportSession;
import net.gaiadesk.model.SupportSessionCreated;
import net.gaiadesk.model.TokenInfo;
import net.gaiadesk.model.TokenRevokeResult;
import net.gaiadesk.model.WakeResult;
import net.gaiadesk.model.Webhook;
import net.gaiadesk.model.WebhookCreated;
import org.jspecify.annotations.Nullable;

/**
 * A GaiaDesk client: your desks through the hosted GaiaDesk API, a desk's own local API, or its LAN gateway.
 *
 * <pre>{@code
 * GaiaDesk gd = GaiaDesk.builder()
 *     .apiKey(System.getenv("GAIADESK_API_KEY"))          // ak_…
 *     .deskToken(System.getenv("GAIADESK_DESK_TOKEN"))    // gdagt_…, verified by the desk
 *     .build();
 * ExecResult r = gd.exec("123456789", "uname -a");
 * }</pre>
 *
 * Every method blocks until its answer; {@link #async()} has the same methods returning
 * {@link java.util.concurrent.CompletableFuture}s. Every failure is a {@link GaiaDeskException} (unchecked) of the
 * class its kind names. Results are the API's own JSON shapes ({@code net.gaiadesk.model}). Desk operations on
 * the hosted API are end-to-end encrypted when the desk can open them ({@link E2eMode}). Thread-safe: share one
 * client.
 */
public final class GaiaDesk {
    /** This SDK's version. */
    public static final String VERSION = "0.1.1";
    /** The hosted API. */
    public static final String DEFAULT_API_URL = "https://api.gaiadesk.net/v1";
    /** The most one file may be through the API (256 MB). */
    public static final long API_FILE_LIMIT = 256L * 1024 * 1024;
    /** The longest one {@code GET …/jobs/{name}/wait} holds, in seconds. */
    public static final int API_WAIT_MAX = 870;

    private final Core core;
    private final DeskOps desk;
    private final HostedOps hosted;
    private final AsyncGaiaDesk async;

    private GaiaDesk(Core core) {
        this.core = core;
        this.desk = new DeskOps(core);
        this.hosted = new HostedOps(core);
        this.async = new AsyncGaiaDesk(this, core.executor);
    }

    /** A builder for the hosted API: give it an {@link Builder#apiKey(String) apiKey}. */
    public static Builder builder() {
        return new Builder(TransportKind.API);
    }

    /** A builder for the desk's own local API (code running ON the desk): its socket or pipe, its admin token or an agent token. */
    public static Builder localBuilder() {
        return new Builder(TransportKind.LOCAL);
    }

    /**
     * A builder for a desk's LAN gateway: {@code https://<desk>:7443/v1}, its self-signed certificate pinned by the
     * SHA-256 fingerprint the desk shows in Settings; an agent token is required.
     */
    public static Builder lanBuilder(String baseUrl, String fingerprint) {
        Builder b = new Builder(TransportKind.LAN);
        b.baseUrl = baseUrl;
        b.fingerprint = fingerprint;
        return b;
    }

    /** Which API this client speaks to. */
    public TransportKind getTransport() {
        return core.transport;
    }

    /** The API's base URL ({@code …/v1}). */
    public String getBaseUrl() {
        return core.baseUrl;
    }

    /** The same operations, as futures. */
    public AsyncGaiaDesk async() {
        return async;
    }

    // ───────────────────────────── desks ─────────────────────────────

    /** {@code GET /desks}: the desks on the account and its team (local: this desk; LAN: it and its paired desks). */
    public DeviceList devices() {
        return hosted.devices(null);
    }

    /** {@code GET /desks}. */
    public DeviceList devices(@Nullable RequestOptions o) {
        return hosted.devices(o);
    }

    /** {@code GET /desks/{id}}: one desk, its reachability, end-to-end key and wake hints. Hosted API only. */
    public DeskDetail device(String deskId) {
        return hosted.device(deskId, null);
    }

    /** {@code GET /desks/{id}}. Hosted API only. */
    public DeskDetail device(String deskId, @Nullable RequestOptions o) {
        return hosted.device(deskId, o);
    }

    /** {@code GET /desks/{id}/reach}: its online and offline history, newest first. Hosted API only. */
    public ReachLog reach(String deskId) {
        return hosted.reach(deskId, null);
    }

    /** {@code GET /desks/{id}/reach?since=&limit=}. Hosted API only. */
    public ReachLog reach(String deskId, @Nullable ReachQuery q) {
        return hosted.reach(deskId, q);
    }

    /** {@code POST /desks/{id}/wake}: ring its doorbell and ask LAN siblings to Wake-on-LAN it. Hosted API only. */
    public WakeResult wake(String deskId) {
        return hosted.wake(deskId, null);
    }

    /** {@code POST /desks/{id}/wake} with {@code wait_s}. Hosted API only. */
    public WakeResult wake(String deskId, @Nullable WakeOptions o) {
        return hosted.wake(deskId, o);
    }

    // ───────────────────────────── exec ─────────────────────────────

    /** {@code POST /desks/{id}/exec}: run ONE command line (given to the desk's shell verbatim). */
    public ExecResult exec(String deskId, String command) {
        return desk.exec(deskId, command, null);
    }

    /**
     * {@code POST /desks/{id}/exec}: run one command line. A command that ran is its result whatever its exit
     * code ({@link ExecOptions#check(boolean)} makes a non-zero exit a {@link CommandException}); one that never
     * ran (refused, unreachable, an {@code admin} refusal, ...) is the typed error.
     */
    public ExecResult exec(String deskId, String command, @Nullable ExecOptions o) {
        return desk.exec(deskId, command, o);
    }

    /** {@code POST /desks/{id}/exec}: run an argument vector (each entry quoted for the shell; {@link Shell#NONE}: run directly). */
    public ExecResult exec(String deskId, List<String> argv) {
        return desk.exec(deskId, List.copyOf(argv), null);
    }

    /** {@code POST /desks/{id}/exec} with an argument vector. */
    public ExecResult exec(String deskId, List<String> argv, @Nullable ExecOptions o) {
        return desk.exec(deskId, List.copyOf(argv), o);
    }

    /** {@code POST /desks/{id}/exec?stream=1}: the output as it comes (Server-Sent Events). */
    public ExecStream execStream(String deskId, String command) {
        return desk.execStream(deskId, command, null);
    }

    /** {@code POST /desks/{id}/exec?stream=1}. */
    public ExecStream execStream(String deskId, String command, @Nullable ExecOptions o) {
        return desk.execStream(deskId, command, o);
    }

    /** {@code POST /desks/{id}/exec?stream=1} with an argument vector. */
    public ExecStream execStream(String deskId, List<String> argv) {
        return desk.execStream(deskId, List.copyOf(argv), null);
    }

    /** {@code POST /desks/{id}/exec?stream=1} with an argument vector. */
    public ExecStream execStream(String deskId, List<String> argv, @Nullable ExecOptions o) {
        return desk.execStream(deskId, List.copyOf(argv), o);
    }

    // ───────────────────────────── files ─────────────────────────────

    /** {@code PUT /desks/{id}/files?path=}: one local file (at most 256 MB); a {@code remote} ending in {@code /} keeps its name. */
    public CopyResult upload(Path local, String deskId, String remote) {
        return desk.upload(local, deskId, remote, null);
    }

    /** {@code PUT /desks/{id}/files?path=}. */
    public CopyResult upload(Path local, String deskId, String remote, @Nullable RequestOptions o) {
        return desk.upload(local, deskId, remote, o);
    }

    /** {@code PUT /desks/{id}/files?path=} with bytes in memory. */
    public CopyResult uploadBytes(byte[] data, String deskId, String remote) {
        return desk.uploadBytes(data, deskId, remote, null);
    }

    /** {@code PUT /desks/{id}/files?path=} with bytes in memory. */
    public CopyResult uploadBytes(byte[] data, String deskId, String remote, @Nullable RequestOptions o) {
        return desk.uploadBytes(data, deskId, remote, o);
    }

    /** {@code PUT /desks/{id}/files?path=} with text (UTF-8). */
    public CopyResult uploadText(String text, String deskId, String remote) {
        return desk.uploadBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), deskId, remote, null);
    }

    /** {@code GET /desks/{id}/files?path=} into a local file (a folder, or a path ending in a separator, keeps the remote name). Written whole or not at all. */
    public CopyResult download(String deskId, String remote, Path local) {
        return desk.download(deskId, remote, local, null);
    }

    /** {@code GET /desks/{id}/files?path=} into a local file. */
    public CopyResult download(String deskId, String remote, Path local, @Nullable RequestOptions o) {
        return desk.download(deskId, remote, local, o);
    }

    /** {@code GET /desks/{id}/files?path=}: the file's bytes. */
    public byte[] downloadBytes(String deskId, String remote) {
        return desk.downloadBytes(deskId, remote, null);
    }

    /** {@code GET /desks/{id}/files?path=}: the file's bytes. */
    public byte[] downloadBytes(String deskId, String remote, @Nullable RequestOptions o) {
        return desk.downloadBytes(deskId, remote, o);
    }

    /** {@code GET /desks/{id}/files?path=} written to {@code out} as it arrives: the number of bytes. */
    public long downloadTo(String deskId, String remote, OutputStream out, @Nullable RequestOptions o) {
        return desk.downloadTo(deskId, remote, out, o);
    }

    // ───────────────────────────── jobs ─────────────────────────────

    /** {@code POST /desks/{id}/jobs}: start a background job (it outlives this call). */
    public Job runJob(String deskId, String name, String command) {
        return desk.runJob(deskId, name, command, null);
    }

    /** {@code POST /desks/{id}/jobs}. */
    public Job runJob(String deskId, String name, String command, @Nullable JobOptions o) {
        return desk.runJob(deskId, name, command, o);
    }

    /** {@code POST /desks/{id}/jobs} with an argument vector. */
    public Job runJob(String deskId, String name, List<String> argv, @Nullable JobOptions o) {
        return desk.runJob(deskId, name, List.copyOf(argv), o);
    }

    /**
     * {@code GET /desks/{id}/jobs/{name}/wait}: the job once it is no longer running ({@code timedOut} false), or
     * as it stands when the timeout ran out. One request holds at most 870 s, so a longer (or no) timeout asks
     * again until the job ends. A held answer's late failure is thrown as its typed error.
     */
    public JobWaitResult waitJob(String deskId, String name) {
        return desk.waitJob(deskId, name, null);
    }

    /** {@code GET /desks/{id}/jobs/{name}/wait?timeout=}. */
    public JobWaitResult waitJob(String deskId, String name, @Nullable WaitOptions o) {
        return desk.waitJob(deskId, name, o);
    }

    /** {@code GET /desks/{id}/jobs}: the desk's background jobs. */
    public List<Job> jobs(String deskId) {
        return desk.jobs(deskId, null);
    }

    /** {@code GET /desks/{id}/jobs}. */
    public List<Job> jobs(String deskId, @Nullable RequestOptions o) {
        return desk.jobs(deskId, o);
    }

    /** {@code DELETE /desks/{id}/jobs/{name}}: stop a job and everything it started. */
    public Job killJob(String deskId, String name) {
        return desk.killJob(deskId, name, null);
    }

    /** {@code DELETE /desks/{id}/jobs/{name}}. */
    public Job killJob(String deskId, String name, @Nullable RequestOptions o) {
        return desk.killJob(deskId, name, o);
    }

    /** {@code GET /desks/{id}/jobs/{name}/logs}: the end of its output. */
    public String jobLogs(String deskId, String name) {
        return desk.jobLogs(deskId, name, null);
    }

    /** {@code GET /desks/{id}/jobs/{name}/logs?tail=}. */
    public String jobLogs(String deskId, String name, @Nullable LogsOptions o) {
        return desk.jobLogs(deskId, name, o);
    }

    /** {@code GET /desks/{id}/jobs/{name}/logs?follow=1}: the log as it grows, until the job ends. */
    public ExecStream followJobLogs(String deskId, String name) {
        return desk.followJobLogs(deskId, name, null);
    }

    /** {@code GET /desks/{id}/jobs/{name}/logs?follow=1&tail=}. */
    public ExecStream followJobLogs(String deskId, String name, @Nullable LogsOptions o) {
        return desk.followJobLogs(deskId, name, o);
    }

    /** {@code GET /desks/{id}/stats}: CPU, memory, disks and running jobs. */
    public DeskStats stats(String deskId) {
        return desk.stats(deskId, null);
    }

    /** {@code GET /desks/{id}/stats}. */
    public DeskStats stats(String deskId, @Nullable RequestOptions o) {
        return desk.stats(deskId, o);
    }

    // ───────────────────────────── tokens ─────────────────────────────

    /**
     * {@code POST /desks/{id}/tokens}, once per desk: one token per desk with its secret (shown once). Token
     * administration is the desk owner's (a signed-in person's own desk). If a later desk fails, the error's JSON
     * carries the tokens already minted ({@code tokens}).
     */
    public MintResult createToken(TokenSpec spec) {
        return desk.createToken(spec);
    }

    /** {@code GET /desks/{id}/tokens}: its agent tokens (never their secrets). */
    public List<TokenInfo> listTokens(String deskId) {
        return desk.listTokens(deskId, null);
    }

    /** {@code GET /desks/{id}/tokens}. */
    public List<TokenInfo> listTokens(String deskId, @Nullable RequestOptions o) {
        return desk.listTokens(deskId, o);
    }

    /** {@code DELETE /desks/{id}/tokens/{token}}: revoke a token (by id or name); its live sessions and jobs end. */
    public TokenRevokeResult revokeToken(String deskId, String token) {
        return desk.revokeToken(deskId, token, null);
    }

    /** {@code DELETE /desks/{id}/tokens/{token}}. */
    public TokenRevokeResult revokeToken(String deskId, String token, @Nullable RequestOptions o) {
        return desk.revokeToken(deskId, token, o);
    }

    // ───────────────────────────── audit, webhooks, support ─────────────────────────────

    /** {@code GET /audit}: one page of audit events about the caller and their desks, newest first. Hosted API only. */
    public List<AuditEvent> audit() {
        return hosted.audit(null);
    }

    /** {@code GET /audit} with filters. Hosted API only. */
    public List<AuditEvent> audit(@Nullable AuditQuery q) {
        return hosted.audit(q);
    }

    /** Every matching audit event, newest first, fetched page by page as it is iterated. Hosted API only. */
    public Iterable<AuditEvent> auditAll(@Nullable AuditQuery q) {
        return hosted.auditAll(q);
    }

    /** {@code GET /webhooks}: the account's subscriptions (never their secrets). Hosted API only. */
    public List<Webhook> webhooks() {
        return hosted.webhooks(null);
    }

    /** {@code GET /webhooks}. Hosted API only. */
    public List<Webhook> webhooks(@Nullable RequestOptions o) {
        return hosted.webhooks(o);
    }

    /** {@code POST /webhooks}: subscribe; the signing secret is in this answer only. Hosted API only. */
    public WebhookCreated createWebhook(WebhookSpec spec) {
        return hosted.createWebhook(spec);
    }

    /** {@code DELETE /webhooks/{id}}: unsubscribe; the id deleted. Hosted API only. */
    public String deleteWebhook(String webhookId) {
        return hosted.deleteWebhook(webhookId, null);
    }

    /** {@code DELETE /webhooks/{id}}. Hosted API only. */
    public String deleteWebhook(String webhookId, @Nullable RequestOptions o) {
        return hosted.deleteWebhook(webhookId, o);
    }

    /** {@code POST /support/sessions}: a support session for the web embed SDK, with its embed token (shown once). Hosted API only. */
    public SupportSessionCreated createSupportSession(@Nullable SupportSessionSpec spec) {
        return hosted.createSupportSession(spec);
    }

    /** {@code GET /support/sessions}: open sessions, newest first. Hosted API only. */
    public List<SupportSession> supportSessions() {
        return hosted.supportSessions(null);
    }

    /** {@code GET /support/sessions?state=&limit=}. Hosted API only. */
    public List<SupportSession> supportSessions(@Nullable SupportSessionQuery q) {
        return hosted.supportSessions(q);
    }

    /** {@code GET /support/sessions/{id}}. Hosted API only. */
    public SupportSession supportSession(String sessionId) {
        return hosted.supportSession(sessionId, null);
    }

    /** {@code GET /support/sessions/{id}}. Hosted API only. */
    public SupportSession supportSession(String sessionId, @Nullable RequestOptions o) {
        return hosted.supportSession(sessionId, o);
    }

    @Override
    public String toString() {
        return "GaiaDesk{" + core.transport + " " + core.baseUrl + "}";
    }

    // ───────────────────────────── builder ─────────────────────────────

    /** Builds a {@link GaiaDesk}. Options that do not apply to the transport are a {@link UsageException} at {@link #build()}. */
    public static final class Builder {
        private final TransportKind kind;
        private @Nullable String apiKey;
        private @Nullable String deskToken;
        @Nullable String baseUrl;
        @Nullable String fingerprint;
        private @Nullable E2eMode e2e;
        private final Map<String, String> e2eKeys = new LinkedHashMap<>();
        private @Nullable Consumer<String> onWarning;
        private RetryPolicy retry = RetryPolicy.defaults();
        private Timeouts timeouts = Timeouts.defaults();
        private Duration connectTimeout = Duration.ofSeconds(30);
        private @Nullable Executor executor;
        private @Nullable HttpClient httpClient;
        private @Nullable String socketPath;
        private @Nullable String token;
        private @Nullable Map<String, String> env;

        private Builder(TransportKind kind) {
            this.kind = kind;
        }

        /** Hosted API: an Atlas API key ({@code ak_…}) or a signed-in person's session token. Required. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The scoped agent token ({@code gdagt_…}) desk operations carry, verified by the desk. Per call: {@link CallOptions#deskToken}. */
        public Builder deskToken(@Nullable String deskToken) {
            this.deskToken = deskToken;
            return this;
        }

        /** Hosted API: another base URL ({@code http(s)://…/v1}); default {@link #DEFAULT_API_URL}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Hosted API: end-to-end encryption of desk operations (default {@link E2eMode#AUTO}). */
        public Builder e2e(E2eMode mode) {
            this.e2e = mode;
            return this;
        }

        /** Hosted API: pin a desk's {@code e2e_pub} (base64url, 32 bytes); a different key from the server is refused. */
        public Builder e2eKey(String deskId, String e2ePub) {
            this.e2eKeys.put(deskId, e2ePub);
            return this;
        }

        /** Hosted API: pin desks' keys, {@code deskId → e2e_pub}. */
        public Builder e2eKeys(Map<String, String> keys) {
            this.e2eKeys.putAll(keys);
            return this;
        }

        /** Where the SDK's warnings go (default: {@code System.Logger} {@code net.gaiadesk}, WARNING). */
        public Builder onWarning(Consumer<String> onWarning) {
            this.onWarning = onWarning;
            return this;
        }

        /** When failed requests are tried again (default {@link RetryPolicy#defaults()}). */
        public Builder retry(RetryPolicy retry) {
            this.retry = retry;
            return this;
        }

        /**
         * How long the SDK waits on the network: for an answer to begin, and for each read of its body (default
         * {@link Timeouts#defaults()}: 16 minutes and 90 s). A peer that stops answering is then an error, never a hang.
         */
        public Builder timeouts(Timeouts timeouts) {
            this.timeouts = timeouts;
            return this;
        }

        /**
         * How long any request waits for its answer to begin: {@link #timeouts(Timeouts)} with this
         * {@link Timeouts#responseTimeout(Duration) responseTimeout} (default 16 minutes; the API holds calls under 15).
         */
        public Builder requestTimeout(Duration timeout) {
            this.timeouts = timeouts.responseTimeout(timeout);
            return this;
        }

        /** How long connecting may take (default 30 s; the API and LAN transports). */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /** Runs {@link #async()} calls and stream readers (default: a shared pool of daemon threads). */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** Hosted API: your own {@link HttpClient} (a proxy, an SSLContext, ...); {@link #connectTimeout} then does not apply. */
        public Builder httpClient(HttpClient client) {
            this.httpClient = client;
            return this;
        }

        /** Local: the socket path or pipe name (default: as {@link LocalApi} says). */
        public Builder socketPath(String socketPath) {
            this.socketPath = socketPath;
            return this;
        }

        /** Local: the desk's local admin token ({@code gdlocal_…}; default: read from its file on each request). */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /** Local: the environment the defaults are read from (default: this process's). */
        public Builder env(Map<String, String> env) {
            this.env = new LinkedHashMap<>(env);
            return this;
        }

        private static @Nullable String nonEmpty(@Nullable String v, String what) {
            if (v == null) return null;
            if (v.trim().isEmpty()) throw Check.usage(what + " must be a non-empty string");
            return v.trim();
        }

        private void only(TransportKind k, boolean set, String what) {
            if (set && kind != k) throw Check.usage(what + " applies to the " + k.label() + " transport only");
        }

        /** The client. */
        public GaiaDesk build() {
            only(TransportKind.API, apiKey != null, "apiKey");
            only(TransportKind.API, e2e != null || !e2eKeys.isEmpty(), "end-to-end encryption (e2e, e2eKeys)");
            only(TransportKind.API, httpClient != null, "httpClient");
            only(TransportKind.LOCAL, socketPath != null || token != null || env != null, "socketPath, token and env");
            String dt = nonEmpty(deskToken, "deskToken (a scoped agent token, gdagt_…)");
            Executor ex = executor != null ? executor : Threads.executor();
            Consumer<String> warn = onWarning != null ? onWarning : m -> System.getLogger("net.gaiadesk").log(System.Logger.Level.WARNING, m);
            String allRetry = System.getProperty("jdk.httpclient.enableAllMethodRetry");
            if (allRetry != null && (allRetry.isEmpty() || Boolean.parseBoolean(allRetry)) && kind != TransportKind.LOCAL) {
                warn.accept("the system property jdk.httpclient.enableAllMethodRetry is set: the JDK's HttpClient may then send a command, "
                        + "an upload or a job start twice when a connection drops before its answer");
            }
            Core core;
            if (kind == TransportKind.API) {
                String key = nonEmpty(apiKey, "apiKey");
                if (key == null) throw Check.usage("apiKey must be a non-empty string");
                String url = (baseUrl != null ? baseUrl : DEFAULT_API_URL).replaceAll("/+$", "");
                if (!url.matches("(?i)^https?://.+")) throw Check.usage("baseUrl must be an http(s) URL: " + Check.quote(baseUrl));
                Map<String, byte[]> pins = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : e2eKeys.entrySet()) {
                    byte[] k = E2eCrypto.deskKey(e.getValue());
                    if (k == null) throw Check.usage("e2eKeys[" + Check.quote(e.getKey()) + "] is not a 32-byte base64url X25519 key");
                    pins.put(Check.desk(e.getKey()), k);
                }
                HttpEngine engine = new JdkHttpEngine(httpClient != null ? httpClient : Transports.defaultClient(connectTimeout));
                core = new Core(TransportKind.API, url, "the GaiaDesk API (" + url + ")", engine, Transports.api(key, dt),
                        new Core.E2eConfig(e2e != null ? e2e : E2eMode.AUTO, pins, warn), retry, timeouts, ex);
            } else if (kind == TransportKind.LOCAL) {
                if (baseUrl != null) throw Check.usage("baseUrl does not apply to the local transport");
                boolean windows = LocalApi.isWindows();
                if (!LocalApi.isSupported()) {
                    throw Check.usage("the local transport needs Java 16 or later on macOS and Linux (Unix domain sockets); this is Java " + System.getProperty("java.version"));
                }
                Map<String, String> e = env != null ? env : System.getenv();
                String home = System.getProperty("user.home", "");
                String target = nonEmpty(socketPath, "socketPath");
                if (target == null) target = windows ? LocalApi.pipeName(e, System.getProperty("user.name", "")) : LocalApi.socketPath(e, home, false);
                core = new Core(TransportKind.LOCAL, "http://localhost/v1", "the desk's local API (" + target + ")", Transports.localEngine(target, windows),
                        Transports.local(nonEmpty(token, "token"), dt, LocalApi.tokenPath(e, home, windows)), null, retry, timeouts, ex);
            } else {
                String url = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
                if (!url.matches("(?i)^https://[^/].*")) {
                    throw Check.usage("the lan transport needs an https:// baseUrl (https://<desk>:7443/v1), not " + Check.quote(baseUrl));
                }
                if (fingerprint == null) throw Check.usage("the lan transport needs the gateway certificate's fingerprint (Settings → GaiaDesk API → LAN gateway)");
                PinnedTrust trust = new PinnedTrust(Lan.normalizeFingerprint(fingerprint));
                String origin = Transports.hostOf(url);
                core = new Core(TransportKind.LAN, url, "the desk's LAN gateway (" + origin + ")", Transports.lanEngine(trust, connectTimeout, origin),
                        Transports.lan(dt), null, retry, timeouts, ex);
            }
            return new GaiaDesk(core);
        }
    }
}
