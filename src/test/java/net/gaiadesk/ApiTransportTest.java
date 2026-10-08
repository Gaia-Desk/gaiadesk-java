package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.gaiadesk.internal.Json;
import net.gaiadesk.model.CopyResult;
import net.gaiadesk.model.ExecResult;
import net.gaiadesk.model.JobWaitResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The API transport in the clear (the desks list no end-to-end key): what goes over the wire, results, error
 * envelopes, streams, retries, timeouts, cancellation and the async variants.
 */
class ApiTransportTest {
    static final String OK = "123456789";
    static MockApi api;

    @BeforeAll
    static void start() throws Exception {
        api = new MockApi();
        api.desks.put(OK, new MockApi.Desk());
    }

    @AfterAll
    static void stop() {
        api.close();
    }

    static GaiaDesk.Builder b() {
        return GaiaDesk.builder().apiKey("ak_test").deskToken("gdagt_test").baseUrl(api.url).onWarning(m -> {});
    }

    static GaiaDesk gd() {
        return b().build();
    }

    static GaiaDesk person() {
        return GaiaDesk.builder().apiKey("session-person").baseUrl(api.url).onWarning(m -> {}).build();
    }

    static MockApi.Recorded last() {
        return api.last();
    }

    static long statsOf(String desk) {
        return api.requests.stream().filter(r -> r.path.equals("/v1/desks/" + desk + "/stats")).count();
    }

    static <T extends Throwable> T fails(Class<T> type, org.junit.jupiter.api.function.Executable f) {
        return assertThrows(type, f);
    }

    // ───────────────────────────── the client ─────────────────────────────

    @Test
    void theBuilderChecksItsOptions() {
        GaiaDesk g = gd();
        assertEquals(TransportKind.API, g.getTransport());
        assertEquals(api.url, g.getBaseUrl());
        assertEquals("https://api.gaiadesk.net/v1", GaiaDesk.builder().apiKey("ak_x").build().getBaseUrl());
        fails(UsageException.class, () -> GaiaDesk.builder().build());
        fails(UsageException.class, () -> GaiaDesk.builder().apiKey(" ").build());
        fails(UsageException.class, () -> GaiaDesk.builder().apiKey("ak_x").baseUrl("ftp://x").build());
        fails(UsageException.class, () -> GaiaDesk.builder().apiKey("ak_x").deskToken("").build());
        fails(UsageException.class, () -> GaiaDesk.builder().apiKey("ak_x").e2eKey(OK, "short").build());
        fails(UsageException.class, () -> GaiaDesk.builder().apiKey("ak_x").socketPath("/x").build());
        fails(UsageException.class, () -> GaiaDesk.localBuilder().apiKey("ak_x").build());
        fails(UsageException.class, () -> GaiaDesk.localBuilder().e2e(E2eMode.REQUIRE).build());
    }

    // ───────────────────────────── what goes over the wire ─────────────────────────────

    @Test
    void everyRequestCarriesTheKeyAndDeskTokenACallMayOverrideTheTokenAndWakeTheDesk() {
        GaiaDesk g = gd();
        g.stats(OK);
        assertEquals("Bearer ak_test", last().header("authorization"));
        assertEquals("gdagt_test", last().header("x-gaiadesk-desk-token"));
        assertTrue(last().header("user-agent").startsWith("gaiadesk-java/" + GaiaDesk.VERSION));
        assertEquals("/v1/desks/" + OK + "/stats", last().path);
        g.stats(OK, new RequestOptions().deskToken("gdagt_other").wake(30));
        assertEquals("gdagt_other", last().header("x-gaiadesk-desk-token"));
        assertEquals(Map.of("wake_s", "30"), last().query);
        person().listTokens(OK);
        assertNull(last().header("x-gaiadesk-desk-token"), "no desk token unless one is given");
    }

    @Test
    void execSendsAnExecSpec() {
        GaiaDesk g = gd();
        ExecResult r = g.exec(OK, "hostname", new ExecOptions().shell(Shell.SH).timeout("10m").cwd("/srv").stdin("in".getBytes(StandardCharsets.UTF_8)));
        assertEquals("POST", last().method);
        assertEquals("application/json", last().header("content-type"));
        assertEquals(Json.parse("{\"command\":\"hostname\",\"shell\":\"sh\",\"cwd\":\"/srv\",\"timeout_secs\":600,\"stdin\":\"in\"}"), last().json());
        assertEquals(0, r.getExit());
        assertEquals(Integer.valueOf(0), r.getRemoteCode());
        assertEquals("ran: hostname é\nstdin: in\n", r.getStdout());
        assertEquals("warn\n", r.getStderr());
        assertEquals("sh", r.getShell());
        assertNull(r.getError());
        g.exec(OK, List.of("ls", "-l"));
        assertEquals(Json.parse("{\"argv\":[\"ls\",\"-l\"]}"), last().json());
        g.execStream(OK, "x").exit();
        assertEquals(Map.of("stream", "1"), last().query);
        assertEquals("text/event-stream", last().header("accept"));
        fails(UsageException.class, () -> g.exec(OK, " "));
        fails(UsageException.class, () -> g.exec(OK, List.of()));
    }

    @Test
    void envAndShellGoInTheSpecsCheckedBeforeAnythingIsSent() {
        GaiaDesk g = gd();
        g.exec(OK, "deploy", new ExecOptions().shell(Shell.POWERSHELL).env(Map.of("STAGE", "prod")).env("EMPTY", ""));
        assertEquals(Json.parse("{\"command\":\"deploy\",\"shell\":\"pwsh\",\"env\":{\"STAGE\":\"prod\",\"EMPTY\":\"\"}}"), last().json());
        g.runJob(OK, "build", "make all", new JobOptions().shell(Shell.BASH).env("CI", "1"));
        assertEquals(Json.parse("{\"name\":\"build\",\"command\":[\"make all\"],\"limits\":{},\"shell\":\"bash\",\"env\":{\"CI\":\"1\"}}"), last().json());
        int before = api.requests.size();
        UsageException e = fails(UsageException.class, () -> g.exec(OK, "x", new ExecOptions().env(Map.of("A=B", "secret-value"))));
        assertFalse(e.getMessage().contains("secret-value"));
        fails(UsageException.class, () -> g.runJob(OK, "-b", "x"));
        assertEquals(before, api.requests.size(), "nothing sent");
    }

    @Test
    void runJobSendsAJobSpecLogsItsTailTokensAMintSpecPerDesk() {
        GaiaDesk g = gd();
        assertEquals("running", g.runJob(OK, "build", "make all", new JobOptions().priority(Priority.LOW).cpu(50).mem("2G").keepAwake(true).cwd("src")).getState());
        assertEquals(Json.parse("{\"name\":\"build\",\"command\":[\"make all\"],\"limits\":{\"priority\":\"low\",\"cpu_percent\":50,\"mem_mb\":2048,\"keep_awake\":true},\"cwd\":\"src\"}"), last().json());
        assertEquals("tail 10\n", g.jobLogs(OK, "build", new LogsOptions().tail(10)));
        assertEquals("/v1/desks/" + OK + "/jobs/build/logs", last().path);
        assertEquals(Map.of("tail", "10"), last().query);
        assertEquals("build", g.jobs(OK).get(0).getName());
        assertEquals("killed", g.killJob(OK, "build").getState());
        assertEquals("DELETE", last().method);
        GaiaDesk owner = person();
        assertEquals("gdagt_minted_secret", owner.createToken(new TokenSpec(OK).name("bot").expires("24h").cwd("/srv").lowPriv(true)).getTokens().get(0).getSecret());
        assertEquals(Json.parse("{\"name\":\"bot\",\"expires_secs\":86400,\"scopes\":[\"exec\",\"cp\",\"jobs\"],\"cwd\":\"/srv\",\"low_priv\":true}"), last().json());
        owner.createToken(new TokenSpec(OK).name("root-bot").scopes(Scopes.EXEC, Scopes.ADMIN));
        assertEquals(Json.parse("[\"exec\",\"admin\"]"), last().json().get("scopes"), "admin is named, never implied");
        fails(UsageException.class, () -> owner.createToken(new TokenSpec(OK).name("x").scopes(Scopes.ADMIN).cwd("/srv")));
        fails(UsageException.class, () -> owner.createToken(new TokenSpec(OK)));
        assertEquals("9f3a1c2b7d004e11", owner.revokeToken(OK, "9f3a1c2b7d004e11").getRevoked());
        assertEquals("/v1/desks/" + OK + "/tokens/9f3a1c2b7d004e11", last().path);
        assertEquals("bot", owner.listTokens(OK).get(0).getLabel());
    }

    @Test
    void waitJobHeldAnswersLateFailuresAndLongTimeoutsWaitedInTurns() {
        GaiaDesk g = gd();
        JobWaitResult done = g.waitJob(OK, "failing", new WaitOptions().timeout("10m"));
        assertEquals(Map.of("timeout", "600"), last().query);
        assertFalse(done.isTimedOut());
        assertEquals(Integer.valueOf(3), done.getJob().getExitCode(), "the job's own code is a result");
        JobWaitResult now = g.waitJob(OK, "slow", new WaitOptions().timeoutSeconds(0));
        assertTrue(now.isTimedOut());
        assertEquals("0", last().query.get("timeout"));
        g.waitJob(OK, "build");
        assertEquals("870", last().query.get("timeout"), "no timeout: the API's longest");
        assertEquals("held", g.waitJob(OK, "held").getJob().getName(), "leading keep-alive spaces are still JSON");
        ConnectionLostException lost = fails(ConnectionLostException.class, () -> g.waitJob(OK, "held-fail"));
        assertEquals("desk_disconnected", lost.getReason());
        OperationFailedException gone = fails(OperationFailedException.class, () -> g.waitJob(OK, "held-gone"));
        assertEquals("failed", gone.getKind());
        assertEquals(422, gone.getJson().get("error").get("status").asInt());
        fails(OperationFailedException.class, () -> g.waitJob(OK, "nope"));
        api.waits.clear();
        assertTrue(g.waitJob(OK, "slow", new WaitOptions().timeoutSeconds(0.3)).isTimedOut());
        assertFalse(api.waits.isEmpty());
        for (String w : api.waits) assertEquals("1", w, "a short timeout is asked of the API as is");
        fails(UsageException.class, () -> g.waitJob(OK, "-x"));
    }

    @Test
    void filesRawBytesUpRawBytesDownAndLocalFiles(@TempDir Path dir) throws Exception {
        GaiaDesk g = gd();
        CopyResult r = g.uploadText("hello", OK, "notes/a.txt");
        assertEquals("PUT", last().method);
        assertEquals(Map.of("path", "notes/a.txt"), last().query);
        assertEquals("application/octet-stream", last().header("content-type"));
        assertEquals("hello", last().body());
        assertEquals("upload", r.getDirection());
        assertEquals(5, r.getBytes());
        assertEquals("hello", new String(g.downloadBytes(OK, "notes/a.txt"), StandardCharsets.UTF_8));
        Path f = dir.resolve("report.txt");
        Files.writeString(f, "report");
        g.upload(f, OK, "docs/");
        assertEquals("docs/report.txt", last().query.get("path"), "a folder keeps the file's name");
        CopyResult down = g.download(OK, "docs/report.txt", dir.resolve("out"));
        assertEquals("report", Files.readString(dir.resolve("out")));
        assertEquals(6, down.getBytes());
        Files.createDirectory(dir.resolve("into"));
        g.download(OK, "x/remote.bin", dir.resolve("into"));
        assertEquals("contents of x/remote.bin\n", Files.readString(dir.resolve("into").resolve("remote.bin")));
        UsageException folder = fails(UsageException.class, () -> g.upload(dir, OK, "x/"));
        assertTrue(folder.getMessage().contains("not available over the API transport"));
        fails(UsageException.class, () -> g.uploadText("x", OK, ""));
        GaiaDeskException local = fails(GaiaDeskException.class, () -> g.upload(dir.resolve("nope"), OK, "x"));
        assertEquals("local", local.getKind());
    }

    // ───────────────────────────── errors ─────────────────────────────

    @Test
    void errorEnvelopesAreTypedWithTheirKindReasonStatusAndRequestId() {
        GaiaDesk noToken = GaiaDesk.builder().apiKey("ak_test").baseUrl(api.url).onWarning(m -> {}).build();
        RefusedException e = fails(RefusedException.class, () -> noToken.stats(OK));
        assertEquals("refused", e.getKind());
        assertEquals("desk_token_required", e.getReason());
        assertEquals(Integer.valueOf(403), e.getStatus());
        assertEquals(Integer.valueOf(254), e.getExitCode());
        assertEquals(OK, e.getDesk());
        assertTrue(e.getRequestId().matches("req_[0-9a-f]{24}"));
        assertEquals(List.of("GET /desks/" + OK + "/stats"), e.getArgv());
        assertTrue(fails(RefusedException.class, () -> noToken.listTokens(OK)).getMessage().contains("signed-in person"));
        UnreachableException off = fails(UnreachableException.class, () -> gd().exec(MockApi.OFFLINE_DESK, "x"));
        assertEquals("offline", off.getKind());
        assertEquals(Integer.valueOf(409), off.getStatus());
        UsageException u = fails(UsageException.class, () -> gd().exec(MockApi.USAGE_DESK, "x"));
        assertEquals(Integer.valueOf(400), u.getStatus());
        UnreachableException unknown = fails(UnreachableException.class, () -> gd().stats("555555555"));
        assertEquals("unknown_desk", unknown.getKind());
    }

    @Test
    void execThatNeverRanIsItsTypedErrorAndAdminRefusalsKeepTheirReason() {
        GaiaDesk g = gd();
        OperationFailedException never = fails(OperationFailedException.class, () -> g.exec(OK, "unreachable-cwd"));
        assertEquals("no_such_cwd", never.getReason());
        assertEquals(Integer.valueOf(255), never.getExitCode());
        RefusedException admin = fails(RefusedException.class, () -> g.exec(OK, "whoami", new ExecOptions().admin(true)));
        assertEquals(Reasons.ADMIN_NOT_ENABLED, admin.getReason());
        assertEquals(Integer.valueOf(254), admin.getExitCode());
        assertTrue(last().json().get("admin").asBoolean(), "admin: true is in the ExecSpec");
        assertEquals(3, g.exec(OK, "fail").getExit(), "a non-zero exit is a result");
        CommandException c = fails(CommandException.class, () -> g.exec(OK, "fail", new ExecOptions().check(true)));
        assertEquals(3, c.getResult().getExit());
        Exit refusedStream = g.execStream(OK, "whoami", new ExecOptions().admin(true)).exit();
        assertEquals(Integer.valueOf(254), refusedStream.getExitCode());
        assertEquals(Reasons.ADMIN_NOT_ENABLED, refusedStream.getError().getReason());
    }

    @Test
    void notAnEnvelopeIsAProtocolErrorNoConnectionIsUnreachableNetwork() {
        ProtocolException p = fails(ProtocolException.class, () -> gd().stats(MockApi.HTML_DESK));
        assertEquals(Integer.valueOf(500), p.getStatus());
        assertTrue(p.getMessage().contains("no error envelope"));
        GaiaDesk down = GaiaDesk.builder().apiKey("ak_test").baseUrl("http://127.0.0.1:1/v1").retry(RetryPolicy.none()).onWarning(m -> {}).build();
        UnreachableException n = fails(UnreachableException.class, () -> down.stats(OK));
        assertEquals("network", n.getKind());
        assertEquals("network", n.getReason());
        assertEquals(Integer.valueOf(255), n.getExitCode());
        Exit exit = down.execStream(OK, "x").exit();
        assertEquals(Integer.valueOf(255), exit.getExitCode());
        assertEquals("unreachable", exit.getError().getKind());
        assertEquals("network", exit.getError().getReason());
    }

    @Test
    void retriesRateLimitsAndReadsButNeverAMutation() {
        GaiaDesk none = b().retry(RetryPolicy.none()).build();
        RefusedException limited = fails(RefusedException.class, () -> none.stats(MockApi.LIMITED_DESK));
        assertEquals(Integer.valueOf(429), limited.getStatus());
        assertEquals(Double.valueOf(7), limited.getRetryAfter());
        long n = statsOf(MockApi.LIMITED_DESK);
        RefusedException waitedTooLong = fails(RefusedException.class,
                () -> b().retry(RetryPolicy.of(2, Duration.ofMillis(10), Duration.ofSeconds(1))).build().stats(MockApi.LIMITED_DESK));
        assertEquals("rate_limited", waitedTooLong.getReason());
        assertEquals(n + 1, statsOf(MockApi.LIMITED_DESK), "a Retry-After longer than maxDelay is not waited for");
        GaiaDesk g = b().retry(RetryPolicy.of(2, Duration.ofMillis(10), Duration.ofMillis(100))).build();
        n = statsOf(MockApi.LIMITED_ONCE_DESK);
        assertEquals(5.0, g.stats(MockApi.LIMITED_ONCE_DESK).getCpuPercent());
        assertEquals(n + 2, statsOf(MockApi.LIMITED_ONCE_DESK), "429 then answered");
        n = statsOf(MockApi.FLAKY_DESK);
        g.stats(MockApi.FLAKY_DESK);
        assertEquals(n + 2, statsOf(MockApi.FLAKY_DESK), "a GET answered 502 is read again");
        fails(ConnectionLostException.class, () -> {
            g.exec(MockApi.FLAKY_DESK, "x");
            g.exec(MockApi.FLAKY_DESK, "x");
        });
        String key = "idem-1";
        g.exec(OK, "x", new ExecOptions().idempotencyKey(key));
        assertEquals(key, last().header("idempotency-key"));
        g.stats(OK, new RequestOptions().idempotencyKey(key));
        assertNull(last().header("idempotency-key"), "POSTs only");
    }

    @Test
    void requestTimeoutsAndCancellation() throws Exception {
        UnreachableException t = fails(UnreachableException.class,
                () -> gd().waitJob(OK, "sleepy", new WaitOptions().requestTimeout(Duration.ofMillis(200))));
        assertEquals("timeout", t.getKind());
        Cancellation c = new Cancellation();
        c.cancel();
        GaiaDeskException i = fails(GaiaDeskException.class, () -> gd().stats(OK, new RequestOptions().cancellation(c)));
        assertEquals("interrupted", i.getKind());
        assertEquals(Integer.valueOf(130), i.getExitCode());
        Cancellation later = new Cancellation();
        CompletableFuture<JobWaitResult> f = gd().async().waitJob(OK, "sleepy", new WaitOptions().cancellation(later));
        Thread.sleep(100);
        later.cancel();
        CompletionException ce = assertThrows(CompletionException.class, f::join);
        assertEquals("interrupted", ((GaiaDeskException) ce.getCause()).getKind());
        CompletableFuture<JobWaitResult> g = gd().async().waitJob(OK, "sleepy", null);
        Thread.sleep(100);
        assertTrue(g.cancel(true));
        assertTrue(g.isCancelled());
    }

    // ───────────────────────────── streams ─────────────────────────────

    @Test
    void execStreamChunksACharacterSplitAcrossEventsAndTheExit() {
        StreamResult r = gd().execStream(OK, "echo hi", new ExecOptions().env("K", "v")).collect();
        assertEquals("ran: echo hi é\nenv: K=v\n", r.getStdout());
        assertEquals("warn\n", r.getStderr());
        assertEquals(Integer.valueOf(0), r.getExit().getExitCode());
        assertEquals(Integer.valueOf(0), r.getExit().getResult().getRemoteCode());
        assertNull(r.getExit().getError());
        StreamResult lost = gd().execStream(OK, "lose").collect();
        assertEquals("connection_lost", lost.getExit().getError().getKind());
        assertEquals(Integer.valueOf(255), lost.getExit().getExitCode());
        List<Chunk> chunks = new ArrayList<>();
        ExecStream s = gd().execStream(OK, List.of("echo", "x"));
        for (Chunk c : s) chunks.add(c);
        assertFalse(chunks.isEmpty());
        assertTrue(s.isDone());
        assertEquals(List.of("POST /desks/" + OK + "/exec"), s.getArgv());
    }

    @Test
    void followJobLogsAndStreamsThatEndBeforeTheyStart() {
        StreamResult f = gd().followJobLogs(OK, "build").collect();
        assertEquals("line1\nline2 é\n", f.getStdout());
        assertEquals("job build exited (exit 0)", f.getExit().getStderrTail());
        assertEquals(Integer.valueOf(0), f.getExit().getExitCode());
        StreamResult missing = gd().followJobLogs(OK, "missing").collect();
        assertEquals("no job named \"missing\"", missing.getExit().getError().getMessage());
        assertEquals(Integer.valueOf(1), missing.getExit().getExitCode());
        GaiaDesk noToken = GaiaDesk.builder().apiKey("ak_test").baseUrl(api.url).onWarning(m -> {}).build();
        ExecStream refused = noToken.execStream(OK, "x");
        assertFalse(refused.iterator().hasNext());
        Exit e = refused.exit();
        assertEquals(Integer.valueOf(254), e.getExitCode());
        assertEquals("desk_token_required", e.getError().getReason());
        ExecStream k = gd().followJobLogs(OK, "build");
        k.kill();
        assertEquals(Integer.valueOf(130), k.exit().getExitCode());
    }

    @Test
    void aStreamAsAFlowPublisher() throws Exception {
        ExecStream s = gd().execStream(OK, "pub");
        StringBuilder out = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        s.asPublisher().subscribe(new Flow.Subscriber<Chunk>() {
            Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                sub = subscription;
                sub.request(1);
            }

            @Override
            public void onNext(Chunk item) {
                if (item.getStream() == Chunk.Stream.STDOUT) out.append(item.getText());
                sub.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("ran: pub é\n", out.toString());
        assertEquals(Integer.valueOf(0), s.exitAsync().get(5, TimeUnit.SECONDS).getExitCode());
    }

    // ───────────────────────────── async ─────────────────────────────

    @Test
    void asyncVariantsCompleteWithTheSameResultsAndErrors() throws Exception {
        AsyncGaiaDesk a = gd().async();
        assertEquals("ran: hostname é\n", a.exec(OK, "hostname", null).get(5, TimeUnit.SECONDS).getStdout());
        assertEquals(5.0, a.stats(OK, null).get(5, TimeUnit.SECONDS).getCpuPercent());
        CompletionException e = assertThrows(CompletionException.class, () -> a.exec(MockApi.OFFLINE_DESK, "x", null).join());
        assertInstanceOf(UnreachableException.class, e.getCause());
        JsonNode j = a.devices(null).get(5, TimeUnit.SECONDS).toJson();
        assertTrue(j.get("devices").isArray());
    }

    @Test
    void resultsAreTheirJsonAndKeepFieldsThisVersionDoesNotKnow() {
        ExecResult r = gd().exec(OK, "hostname");
        assertEquals("the GaiaDesk server", r.getRoute());
        assertEquals(r, gd().exec(OK, "hostname"));
        assertEquals(7, r.toJson().get("duration_ms").asInt());
        assertTrue(r.toString().contains("\"stdout\""));
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), new Chunk(Chunk.Stream.STDOUT, "x".getBytes(StandardCharsets.UTF_8)).getData());
    }
}
