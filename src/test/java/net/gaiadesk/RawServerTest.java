package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.gaiadesk.internal.Threads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A server or proxy that drops or stalls a connection, on a raw socket (no HTTP framework): the SDK fails with a
 * clear transport error within its configured timeouts, retries only where the policy allows, and never hangs.
 */
class RawServerTest {
    private static final String D = "123456789";
    /** A hang shows as this, not as a stuck run. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    private static Duration seconds(double s) {
        return Duration.ofMillis((long) (s * 1000));
    }

    private static GaiaDesk gd(RawServer s, int retries, double idle, double response) {
        return GaiaDesk.builder().apiKey("ak_t").deskToken("gdagt_t").baseUrl(s.url).e2e(E2eMode.OFF).onWarning(m -> {})
                .retry(RetryPolicy.of(retries, Duration.ofMillis(5), Duration.ofMillis(100)))
                .timeouts(Timeouts.defaults().idleTimeout(seconds(idle)).responseTimeout(seconds(response)))
                .build();
    }

    private static GaiaDesk gd(RawServer s) {
        return gd(s, 2, 1, 30);
    }

    /** What {@code f} threw, as {@code type}, and how long it took; a hang fails the test after {@link #BOUND}. */
    private static <T extends Throwable> T fails(Class<T> type, Executable f, long[] took) {
        long t0 = System.nanoTime();
        T e = assertTimeoutPreemptively(BOUND, () -> assertThrows(type, f), "no answer within " + BOUND.getSeconds() + " s: the SDK hung");
        if (took != null) took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        return e;
    }

    private static <T extends Throwable> T fails(Class<T> type, Executable f) {
        return fails(type, f, null);
    }

    private static void inRange(int n, int lo, int hi, String what) {
        assertTrue(n >= lo && n <= hi, what + ": " + n + " not in " + lo + ".." + hi);
    }

    @ParameterizedTest
    @EnumSource(value = RawServer.Mode.class, names = {"CLOSE_BEFORE_RESPONSE", "RESET_BEFORE_RESPONSE"})
    void droppedBeforeAnyResponseByteAReadIsRetriedThenUnreachableNetwork(RawServer.Mode mode) throws IOException {
        try (RawServer s = new RawServer(mode)) {
            GaiaDesk g = gd(s);
            UnreachableException e = fails(UnreachableException.class, () -> g.downloadBytes(D, "/tmp/x"));
            assertEquals("network", e.getKind());
            assertEquals("network", e.getReason());
            assertEquals(List.of("GET /desks/123456789/files"), e.getArgv());
            // The first try and the SDK's two retries: a GET is safe to send again. (The JDK's HttpClient itself
            // re-sends a GET or HEAD, never a request of another method, when a pooled connection it reused was
            // closed before any answer: once on JDK 11-17, up to jdk.httpclient.redirects.retrylimit (5) times on
            // later JDKs. The next test pins that a request with a body is never re-sent.)
            inRange(s.count("GET"), 3, 18, "GETs");
            int before = s.count("GET");
            fails(UnreachableException.class, () -> g.stats(D));
            inRange(s.count("GET") - before, 3, 18, "GETs");
            GaiaDesk once = gd(s, 0, 1, 30);
            before = s.count("GET");
            fails(UnreachableException.class, () -> once.stats(D));
            inRange(s.count("GET") - before, 1, 6, "GETs");
        }
    }

    @ParameterizedTest
    @EnumSource(value = RawServer.Mode.class, names = {"CLOSE_BEFORE_RESPONSE", "RESET_BEFORE_RESPONSE", "CLOSE_AFTER_BODY"})
    void droppedBeforeAnyResponseByteALargeUploadOrAnExecIsNeverSentTwice(RawServer.Mode mode, @TempDir Path dir) throws IOException {
        try (RawServer s = new RawServer(mode)) {
            GaiaDesk g = gd(s);
            byte[] big = new byte[4 * 1024 * 1024];
            UnreachableException e = fails(UnreachableException.class, () -> g.uploadBytes(big, D, "/tmp/big"));
            assertEquals("network", e.getKind());
            assertEquals(1, s.count("PUT"));
            Path file = dir.resolve("big.bin");
            Files.write(file, big);
            fails(UnreachableException.class, () -> g.upload(file, D, "/tmp/from-file"));
            assertEquals(2, s.count("PUT"));
            fails(UnreachableException.class, () -> g.exec(D, "deploy"));
            assertEquals(1, s.count("POST"));
            Exit st = assertTimeoutPreemptively(BOUND, () -> g.execStream(D, "deploy").exit());
            assertEquals("unreachable", st.getError().getKind());
            assertEquals(Integer.valueOf(255), st.getExitCode());
            assertEquals(2, s.count("POST"));
            fails(UnreachableException.class, () -> g.runJob(D, "nightly", "make"));
            assertEquals(3, s.count("POST"));
            fails(UnreachableException.class, () -> g.exec(D, "deploy", new ExecOptions().idempotencyKey("k-1")));
            assertEquals(4, s.count("POST"), "an Idempotency-Key does not make a POST retryable");
            fails(UnreachableException.class, () -> g.killJob(D, "nightly"));
            assertEquals(1, s.count("DELETE"));
            fails(UnreachableException.class, () -> g.revokeToken(D, "ci"));
            assertEquals(2, s.count("DELETE"));
            assertEquals(0, s.count("GET"));
        }
    }

    @Test
    void stalledMidDownloadConnectionLostTimeoutWithinTheIdleTimeoutNoPartialFile(@TempDir Path dir) throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STALL_MID_BODY)) {
            GaiaDesk g = gd(s, 2, 1, 30);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long[] took = new long[1];
            ConnectionLostException e = fails(ConnectionLostException.class, () -> g.downloadTo(D, "/tmp/x", out, null), took);
            assertEquals("hello", new String(out.toByteArray(), StandardCharsets.UTF_8), "what came before the stall is delivered");
            assertEquals("timeout", e.getKind());
            assertEquals("timeout", e.getReason());
            assertEquals(Integer.valueOf(255), e.getExitCode());
            assertTrue(e.getMessage().contains("idleTimeout"), e.getMessage());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
            assertEquals(1, s.count("GET"), "an answer that had begun is not asked again");
            Path file = dir.resolve("x");
            fails(ConnectionLostException.class, () -> g.download(D, "/tmp/x", file));
            assertFalse(Files.exists(file), "no partial file");
            try (Stream<Path> left = Files.list(dir)) {
                assertEquals(List.of(), left.collect(Collectors.toList()), "no temporary file left");
            }
            fails(ConnectionLostException.class, () -> g.downloadBytes(D, "/tmp/x"));
        }
    }

    @Test
    void stalledMidJsonConnectionLostTimeout() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STALL_MID_JSON)) {
            GaiaDesk g = gd(s, 0, 1, 30);
            long[] took = new long[1];
            ConnectionLostException e = fails(ConnectionLostException.class, () -> g.stats(D), took);
            assertEquals("timeout", e.getKind());
            assertEquals("timeout", e.getReason());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
        }
    }

    @Test
    void stalledMidStreamTheStreamEndsWithATimeoutError() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STALL_MID_EVENTS)) {
            GaiaDesk g = gd(s);
            StreamResult r = assertTimeoutPreemptively(BOUND, () -> g.execStream(D, "tail -f log").collect());
            assertEquals("hi", r.getStdout());
            assertNotNull(r.getExit().getError());
            assertEquals("connection_lost", r.getExit().getError().getKind());
            assertEquals("timeout", r.getExit().getError().getReason());
            assertEquals(Integer.valueOf(255), r.getExit().getExitCode());
            Exit logs = assertTimeoutPreemptively(BOUND, () -> g.followJobLogs(D, "build").exit());
            assertEquals("connection_lost", logs.getError().getKind());
        }
    }

    @Test
    void aSilentServerUnreachableTimeoutWithinTheResponseTimeoutNotRetried() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.SILENT)) {
            GaiaDesk g = gd(s, 2, 1, 1);
            long[] took = new long[1];
            UnreachableException e = fails(UnreachableException.class, () -> g.stats(D), took);
            assertEquals("timeout", e.getKind());
            assertEquals("timeout", e.getReason());
            assertTrue(e.getMessage().contains("within 1 s (responseTimeout)"), e.getMessage());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
            fails(UnreachableException.class, () -> g.uploadBytes(new byte[4 * 1024 * 1024], D, "/tmp/big"));
            assertEquals(1, s.count("GET"));
            assertEquals(1, s.count("PUT"));
            Cancellation c = new Cancellation();
            Threads.scheduler().schedule(c::cancel, 200, TimeUnit.MILLISECONDS);
            GaiaDesk patient = gd(s, 2, 1, 600);
            GaiaDeskException i = fails(GaiaDeskException.class, () -> patient.stats(D, new RequestOptions().cancellation(c)), took);
            assertEquals("interrupted", i.getKind());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
        }
    }

    @Test
    void stress300DroppedRequestsNeverHang() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.CLOSE_BEFORE_RESPONSE)) {
            GaiaDesk g = gd(s, 1, 1, 30);
            byte[] up = new byte[512 * 1024];
            RawServer.Mode[] modes = {RawServer.Mode.CLOSE_BEFORE_RESPONSE, RawServer.Mode.RESET_BEFORE_RESPONSE, RawServer.Mode.CLOSE_AFTER_BODY};
            for (int i = 0; i < 300; i++) {
                s.mode = modes[i % 3];
                Executable op = i % 2 == 0 ? () -> g.downloadBytes(D, "/tmp/x") : () -> g.uploadBytes(up, D, "/tmp/up");
                UnreachableException e = fails(UnreachableException.class, op);
                assertEquals("network", e.getKind(), "iteration " + i + " (" + s.mode + ")");
            }
            assertEquals(150, s.count("PUT"), "every upload sent exactly once");
            inRange(s.count("GET"), 300, 1800, "every read tried twice (plus the JDK's own re-sends of a GET)");
        }
    }

    @Test
    void theLocalTransportIsBoundedTheSameWay() throws Exception {
        assumeTrue(LocalLanTest.unixOk(), "Unix domain sockets need Java 16+ on macOS or Linux");
        Path sockDir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "gd");
        String sock = sockDir.resolve("api.sock").toString();
        try (RawServer s = new RawServer(RawServer.Mode.STALL_MID_JSON);
                LocalLanTest.UnixFront front = new LocalLanTest.UnixFront(sock, Integer.parseInt(s.url.replaceAll(".*:(\\d+)/v1$", "$1")))) {
            GaiaDesk g = GaiaDesk.localBuilder().socketPath(sock).token("gdlocal_t").retry(RetryPolicy.none()).onWarning(m -> {})
                    .timeouts(Timeouts.of(seconds(1), seconds(1))).build();
            assertTrue(front.accept.isAlive());
            long[] took = new long[1];
            ConnectionLostException lost = fails(ConnectionLostException.class, () -> g.stats(D), took);
            assertEquals("timeout", lost.getKind());
            assertTrue(lost.getMessage().contains("idleTimeout"), lost.getMessage());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
            s.mode = RawServer.Mode.STALL_MID_EVENTS;
            StreamResult r = assertTimeoutPreemptively(BOUND, () -> g.execStream(D, "tail -f log").collect());
            assertEquals("hi", r.getStdout());
            assertEquals("connection_lost", r.getExit().getError().getKind());
            assertEquals("timeout", r.getExit().getError().getReason());
            s.mode = RawServer.Mode.SILENT;
            UnreachableException silent = fails(UnreachableException.class, () -> g.stats(D), took);
            assertEquals("timeout", silent.getKind());
            assertTrue(silent.getMessage().contains("(responseTimeout)"), silent.getMessage());
            assertTrue(took[0] < 5000, "took " + took[0] + " ms");
        } finally {
            Files.deleteIfExists(sockDir.resolve("api.sock"));
            Files.deleteIfExists(sockDir);
        }
    }

    // ───────────────────────────── the retry rule ─────────────────────────────

    @Test
    void aConnectionNeverMadeIsRetriedForAnyMethod() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + "/v1";
        GaiaDesk once = GaiaDesk.builder().apiKey("ak_t").deskToken("gdagt_t").baseUrl(url).e2e(E2eMode.OFF).onWarning(m -> {})
                .retry(RetryPolicy.none()).build();
        long[] took = new long[1];
        UnreachableException refused = fails(UnreachableException.class, () -> once.exec(D, "deploy"), took);
        assertEquals("network", refused.getKind());
        assertTrue(took[0] < 2000, "took " + took[0] + " ms");
        // The server appears ~100 ms later; backoff 200 ms × 2^n × 0.5–1.0 waits at least 100 + 200 ms in two retries.
        GaiaDesk g = GaiaDesk.builder().apiKey("ak_t").deskToken("gdagt_t").baseUrl(url).e2e(E2eMode.OFF).onWarning(m -> {})
                .retry(RetryPolicy.of(2, Duration.ofMillis(200), Duration.ofSeconds(1))).build();
        java.util.concurrent.CompletableFuture<Integer> ran = java.util.concurrent.CompletableFuture.supplyAsync(() -> g.exec(D, "deploy").getExit());
        Thread.sleep(100);
        try (RawServer s = new RawServer(RawServer.Mode.OK, port)) {
            assertEquals(Integer.valueOf(0), ran.get(10, TimeUnit.SECONDS));
            assertEquals(1, s.count("POST"), "a POST whose connection was never made is sent again, and runs once");
        }
    }

    private static GaiaDesk statusGd(RawServer s, int retries) {
        return GaiaDesk.builder().apiKey("ak_t").deskToken("gdagt_t").baseUrl(s.url).e2e(E2eMode.OFF).onWarning(m -> {})
                .retry(RetryPolicy.of(retries, Duration.ofMillis(5), Duration.ofMillis(50))).build();
    }

    private static void status(RawServer s, int status, String reason, String retryAfter) {
        s.mode = RawServer.Mode.STATUS;
        s.status = status;
        s.reason = reason;
        s.retryAfter = retryAfter;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {502, 503, 504})
    void a5xxIsRetriedForAGetOnly(int code) throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STATUS)) {
            status(s, code, code == 504 ? "timeout" : "unavailable", null);
            GaiaDesk g = statusGd(s, 2);
            GaiaDeskException e = fails(GaiaDeskException.class, () -> g.stats(D));
            assertEquals(Integer.valueOf(code), e.getStatus());
            assertEquals(3, s.count("GET"));
            fails(GaiaDeskException.class, () -> g.exec(D, "deploy"));
            assertEquals(1, s.count("POST"));
            fails(GaiaDeskException.class, () -> g.killJob(D, "nightly"));
            assertEquals(1, s.count("DELETE"));
        }
    }

    @Test
    void a503ThatIsPermanentIsFinalAndRetryAfterIsHonoured() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STATUS)) {
            GaiaDesk g = statusGd(s, 2);
            for (String permanent : new String[] {"api_disabled", "desk_ops_disabled", "local_api_off"}) {
                status(s, 503, permanent, null);
                int before = s.count("GET");
                fails(UnreachableException.class, () -> g.stats(D));
                assertEquals(before + 1, s.count("GET"), permanent);
            }
            status(s, 503, "unavailable", "1");
            GaiaDesk once = statusGd(s, 1);
            long[] took = new long[1];
            int before = s.count("GET");
            fails(UnreachableException.class, () -> once.stats(D), took);
            assertEquals(before + 2, s.count("GET"));
            assertTrue(took[0] >= 900, "Retry-After: 1 was waited for (" + took[0] + " ms)");
        }
    }

    @Test
    void a429OrAKeyInFlightIsRetriedForAnyMethodAfterRetryAfter() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STATUS)) {
            GaiaDesk g = statusGd(s, 2);
            status(s, 429, "rate_limited", "0");
            RefusedException limited = fails(RefusedException.class, () -> g.exec(D, "deploy"));
            assertEquals(Integer.valueOf(429), limited.getStatus());
            assertEquals(3, s.count("POST"));
            status(s, 429, "desk_busy", "120");
            long[] took = new long[1];
            RefusedException later = fails(RefusedException.class, () -> g.uploadBytes(new byte[16], D, "/tmp/x"), took);
            assertEquals(Double.valueOf(120), later.getRetryAfter(), "a Retry-After over maxRetryWait is thrown at once, carrying it");
            assertEquals(1, s.count("PUT"));
            assertTrue(took[0] < 1000, "took " + took[0] + " ms");
            status(s, 409, "idempotency_key_in_flight", null);
            fails(RefusedException.class, () -> g.exec(D, "deploy", new ExecOptions().idempotencyKey("k-2")));
            assertEquals(6, s.count("POST"));
            fails(RefusedException.class, () -> g.killJob(D, "nightly"));
            assertEquals(3, s.count("DELETE"));
            status(s, 409, "e2e_required", null);
            fails(RefusedException.class, () -> g.stats(D));
            assertEquals(1, s.count("GET"), "another 409 is final");
        }
    }

    @Test
    void timeoutsAreNeverRetried() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.STALL_MID_JSON)) {
            GaiaDesk g = gd(s, 2, 1, 1);
            fails(ConnectionLostException.class, () -> g.stats(D));
            assertEquals(1, s.count("GET"), "an answer that had begun");
            s.mode = RawServer.Mode.SILENT;
            fails(UnreachableException.class, () -> g.stats(D));
            assertEquals(2, s.count("GET"), "no answer within the response timeout");
        }
    }

    @Test
    void onAReusedConnectionThatClosesTheJdkNeverResendsAPostPutOrDelete() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.KEEP_ALIVE_THEN_CLOSE)) {
            GaiaDesk g = statusGd(s, 2);
            String[] methods = {"DELETE", "POST", "PUT", "DELETE"};
            Executable[] calls = {() -> g.killJob(D, "nightly"), () -> g.exec(D, "deploy"), () -> g.uploadBytes(new byte[1024], D, "/tmp/x"),
                    () -> g.revokeToken(D, "ci")};
            for (int i = 0; i < calls.length; i++) {
                assertEquals(D, assertTimeoutPreemptively(BOUND, () -> g.stats(D)).getDesk(), "a fresh connection answers");
                int before = s.count(methods[i]);
                int reused = s.reused(methods[i]);
                UnreachableException e = fails(UnreachableException.class, calls[i]);
                assertEquals("network", e.getKind());
                assertEquals(before + 1, s.count(methods[i]), methods[i] + " reached the server exactly once");
                assertEquals(reused + 1, s.reused(methods[i]), methods[i] + " went on the reused connection");
            }
            // A GET on a reused connection that closes is sent again (by the JDK, or by the SDK): it is a read.
            assertEquals(D, assertTimeoutPreemptively(BOUND, () -> g.stats(D)).getDesk());
            int reused = s.reused("GET");
            assertEquals(D, assertTimeoutPreemptively(BOUND, () -> g.stats(D)).getDesk());
            assertTrue(s.reused("GET") > reused, "the GET went on the reused connection first");
        }
    }

    @Test
    void withNoRetriesEveryModeIsOneAttempt() throws IOException {
        try (RawServer s = new RawServer(RawServer.Mode.CLOSE_BEFORE_RESPONSE)) {
            GaiaDesk g = statusGd(s, 0);
            int gets = 0;
            int posts = 0;
            for (RawServer.Mode m : new RawServer.Mode[] {RawServer.Mode.CLOSE_BEFORE_RESPONSE, RawServer.Mode.RESET_BEFORE_RESPONSE, RawServer.Mode.CLOSE_AFTER_BODY}) {
                s.mode = m;
                fails(UnreachableException.class, () -> g.exec(D, "deploy"));
                assertEquals(++posts, s.count("POST"), m.toString());
                int before = s.count("GET");
                fails(UnreachableException.class, () -> g.stats(D));
                inRange(s.count("GET") - before, 1, 6, m + " (the JDK's own GET re-sends)");
                gets = s.count("GET");
            }
            Object[][] statuses = {{502, "x", null}, {503, "x", null}, {504, "timeout", null}, {429, "rate_limited", "0"}, {409, "idempotency_key_in_flight", null}};
            for (Object[] st : statuses) {
                status(s, (Integer) st[0], (String) st[1], (String) st[2]);
                fails(GaiaDeskException.class, () -> g.stats(D));
                assertEquals(++gets, s.count("GET"), "HTTP " + st[0]);
                fails(GaiaDeskException.class, () -> g.exec(D, "deploy"));
                assertEquals(++posts, s.count("POST"), "HTTP " + st[0]);
            }
        }
    }

    @Test
    void theBackoffAndTheRetryAfterCap() {
        RetryPolicy d = RetryPolicy.defaults();
        assertEquals(2, d.getMaxRetries());
        assertEquals(Duration.ofMillis(250), d.getBaseDelay());
        assertEquals(Duration.ofSeconds(8), d.getMaxDelay());
        assertEquals(Duration.ofSeconds(60), d.getMaxRetryWait());
        assertEquals(125, d.backoffMillis(0, 0.5));
        assertEquals(250, d.backoffMillis(0, 1.0));
        assertEquals(1000, d.backoffMillis(2, 1.0));
        assertEquals(8000, d.backoffMillis(5, 1.0), "capped at maxDelay");
        assertEquals(4000, d.backoffMillis(40, 0.5));
        for (int i = 0; i < 200; i++) {
            long first = d.delayMillis(0, null);
            assertTrue(first >= 125 && first <= 250, "jitter 0.5–1.0: " + first);
            long second = d.delayMillis(1, null);
            assertTrue(second >= 250 && second <= 500, "jitter 0.5–1.0: " + second);
        }
        assertEquals(-1, d.delayMillis(2, null), "out of retries: 3 attempts in all");
        assertEquals(60000, d.delayMillis(0, 60.0));
        assertEquals(-1, d.delayMillis(0, 60.5), "a Retry-After over 60 s is not waited for");
        assertEquals(0, d.delayMillis(0, -3.0));
        assertEquals(-1, RetryPolicy.none().delayMillis(0, 0.0));
        assertEquals(Duration.ofSeconds(60), RetryPolicy.of(1, Duration.ZERO, Duration.ZERO).getMaxRetryWait());
        assertThrows(UsageException.class, () -> RetryPolicy.of(-1, Duration.ZERO, Duration.ZERO));
        assertThrows(UsageException.class, () -> RetryPolicy.of(1, Duration.ofMillis(-1), Duration.ZERO));
        assertThrows(UsageException.class, () -> RetryPolicy.of(1, Duration.ZERO, Duration.ofMillis(-1)));
        assertThrows(UsageException.class, () -> RetryPolicy.of(1, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(-1)));
    }

    @Test
    void timeoutsAreChecked() {
        assertThrows(UsageException.class, () -> Timeouts.defaults().idleTimeout(Duration.ZERO));
        assertThrows(UsageException.class, () -> Timeouts.defaults().responseTimeout(Duration.ofSeconds(-2)));
        assertThrows(UsageException.class, () -> Timeouts.of(Duration.ofSeconds(1), Duration.ofMillis(-1)));
        assertThrows(UsageException.class, () -> GaiaDesk.builder().apiKey("ak").requestTimeout(Duration.ZERO).build());
        Timeouts none = Timeouts.none();
        assertEquals(null, none.getResponseTimeout());
        assertEquals(null, none.getIdleTimeout());
        assertEquals(none.toString(), Timeouts.defaults().responseTimeout(null).idleTimeout(null).toString());
        GaiaDesk.builder().apiKey("ak").timeouts(none).build();
        assertEquals(Duration.ofMinutes(16), Timeouts.defaults().getResponseTimeout());
        assertEquals(Duration.ofSeconds(90), Timeouts.defaults().getIdleTimeout());
    }
}
