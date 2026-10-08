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
