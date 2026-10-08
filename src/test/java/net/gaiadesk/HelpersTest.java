package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.gaiadesk.internal.Check;
import net.gaiadesk.internal.SseEvent;
import net.gaiadesk.internal.SseParser;
import net.gaiadesk.internal.Utf8Stream;
import org.junit.jupiter.api.Test;

/** The pure pieces: SSE parsing, UTF-8 carried across chunks, argument checks, paths, fingerprints, signatures, backoff. */
class HelpersTest {
    @Test
    void sseEventsSplitAnywhereCrlfAcrossChunksCommentsMultiLineDataAnUnterminatedLastEvent() {
        String text = ": keep-alive\r\nevent: stdout\r\ndata: {\"event\":\"stdout\",\"data\":\"a\"}\r\n\r\n:ping\n\nevent: x\ndata: line1\ndata: line2\n\ndata: {\"event\":\"exit\",\"exit\":0}";
        List<SseEvent> want = Arrays.asList(new SseEvent("stdout", "{\"event\":\"stdout\",\"data\":\"a\"}"), new SseEvent("x", "line1\nline2"),
                new SseEvent("message", "{\"event\":\"exit\",\"exit\":0}"));
        for (int size : new int[] {1, 2, 3, 7, text.length()}) {
            SseParser p = new SseParser();
            List<SseEvent> got = new ArrayList<>();
            for (int i = 0; i < text.length(); i += size) got.addAll(p.feed(text.substring(i, Math.min(text.length(), i + size))));
            got.addAll(p.end());
            assertEquals(want, got, "chunks of " + size);
        }
        SseParser p = new SseParser();
        assertEquals(List.of(), p.feed("data: a\r"));
        assertEquals(List.of(), p.feed("\ndata:b\r\r"), "a trailing \\r may be half of \\r\\n");
        assertEquals(List.of(new SseEvent("message", "a\nb")), p.feed("\n"));
    }

    @Test
    void utf8ACharacterSplitAcrossChunksWaitsForItsEnd() {
        byte[] b = "aé€😀z".getBytes(StandardCharsets.UTF_8);
        for (int cut = 0; cut <= b.length; cut++) {
            Utf8Stream d = new Utf8Stream();
            String s = d.decode(Arrays.copyOfRange(b, 0, cut)) + d.decode(Arrays.copyOfRange(b, cut, b.length)) + d.flush();
            assertEquals("aé€😀z", s, "cut at " + cut);
        }
        Utf8Stream d = new Utf8Stream();
        assertEquals("a", d.decode(new byte[] {'a', (byte) 0xc3}));
        assertEquals("\ufffd", d.flush(), "an unfinished character at the end");
    }

    @Test
    void durationsAsTheApiTakesThem() {
        assertEquals(90, Check.seconds("90", "t"));
        assertEquals(2, Check.seconds(1.2, "t"));
        assertEquals(30, Check.seconds("30s", "t"));
        assertEquals(600, Check.seconds("10m", "t"));
        assertEquals(5400, Check.seconds("1h30m", "t"));
        assertEquals(604800, Check.seconds("7d", "t"));
        assertEquals(1209600, Check.seconds("2w", "t"));
        assertThrows(UsageException.class, () -> Check.seconds("5 fortnights", "t"));
        assertThrows(UsageException.class, () -> Check.seconds("soon", "t"));
        assertThrows(UsageException.class, () -> Check.seconds(-1, "t"));
        assertEquals(2048, Check.memMb("2G"));
        assertEquals(512, Check.memMb("512M"));
        assertEquals(300, Check.memMb("300"));
        assertThrows(UsageException.class, () -> Check.memMb("lots"));
    }

    @Test
    void argumentChecksSayWhatIsWrongAndNeverAnEnvValue() {
        assertEquals("123456789", Check.desk(" 123456789 "));
        assertThrows(UsageException.class, () -> Check.desk(""));
        assertThrows(UsageException.class, () -> Check.desk("-x"));
        assertThrows(UsageException.class, () -> Check.desk("12 34"));
        assertEquals("build.1_a-b", Check.jobName("build.1_a-b"));
        assertThrows(UsageException.class, () -> Check.jobName("-x"));
        assertThrows(UsageException.class, () -> Check.jobName("a/b"));
        UsageException e = assertThrows(UsageException.class, () -> Check.env(Map.of("A=B", "secret-value")));
        assertFalse(e.getMessage().contains("secret-value"));
        assertEquals("usage", e.getKind());
        assertThrows(UsageException.class, () -> Check.env(Map.of("A", "nul\0")));
        assertThrows(UsageException.class, () -> Check.cwd(" "));
        assertThrows(UsageException.class, () -> new ExecOptions().wake(121));
        assertThrows(UsageException.class, () -> new JobOptions().cpu(0));
        assertThrows(UsageException.class, () -> new JobOptions().shell(Shell.NONE));
        assertThrows(UsageException.class, () -> new RequestOptions().idempotencyKey("tab\there"));
        assertThrows(UsageException.class, () -> new TokenSpec("123456789").scopes());
        assertThrows(UsageException.class, () -> new WebhookSpec("http://x"));
        assertThrows(UsageException.class, () -> new WebhookSpec("https://x"));
        assertEquals("pwsh", Shell.POWERSHELL.wire());
    }

    @Test
    void fingerprintsAreNormalized() {
        String hex = "AB".repeat(32);
        String want = String.join(":", java.util.Collections.nCopies(32, "ab"));
        assertEquals(want, Lan.normalizeFingerprint(hex));
        assertEquals(want, Lan.normalizeFingerprint("SHA256:" + want.toUpperCase()));
        assertEquals(want, Lan.normalizeFingerprint(want.replace(":", " ")));
        assertThrows(UsageException.class, () -> Lan.normalizeFingerprint("ab:cd"));
    }

    @Test
    void localApiPaths() {
        assertEquals("ada_lovelace", LocalApi.pipeUser("Ada Lovelace"));
        assertEquals("user", LocalApi.pipeUser(""));
        assertEquals(64, LocalApi.pipeUser("x".repeat(80)).length());
        assertEquals("\\\\.\\pipe\\gaiadesk-api-ada", LocalApi.pipeName(Map.of("USERNAME", "Ada"), "other"));
        assertEquals("\\\\.\\pipe\\gaiadesk-api-other", LocalApi.pipeName(Map.of(), "other"));
        assertEquals("\\\\.\\pipe\\mine", LocalApi.pipeName(Map.of("GAIADESK_API_PIPE", "\\\\.\\pipe\\mine"), "x"));
        assertEquals("/home/a/.gaiadesk/api.sock", LocalApi.socketPath(Map.of(), "/home/a", false));
        assertEquals("/run/gd/api.sock", LocalApi.socketPath(Map.of("GAIADESK_API_DIR", "/run/gd"), "/home/a", false));
        assertEquals("/home/a/.gaiadesk/api.sock", LocalApi.socketPath(Map.of("GAIADESK_API_DIR", "relative"), "/home/a", false), "a relative dir is ignored");
        assertEquals("C:\\Users\\a\\.gaiadesk\\api-token", LocalApi.tokenPath(Map.of(), "C:\\Users\\a", true));
        assertEquals("D:\\gd\\api-token", LocalApi.tokenPath(Map.of("GAIADESK_API_DIR", "D:\\gd"), "C:\\Users\\a", true));
    }

    @Test
    void webhookSignaturesVerifyInConstantTimeWithinFiveMinutes() {
        byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        String h = Webhooks.sign("whsec_x", body, 1791300000L);
        assertTrue(h.matches("t=1791300000,v1=[0-9a-f]{64}"));
        assertTrue(Webhooks.verify("whsec_x", h, body, 1791300100L));
        assertFalse(Webhooks.verify("whsec_x", h, body, 1791300301L), "too old");
        assertFalse(Webhooks.verify("whsec_y", h, body, 1791300000L), "another secret");
        assertFalse(Webhooks.verify("whsec_x", h, "{}".getBytes(StandardCharsets.UTF_8), 1791300000L), "another body");
        assertFalse(Webhooks.verify("whsec_x", "t=x,v1=zz", body, 1791300000L));
        // The API reference's own algorithm: HMAC-SHA256 of "<t>.<raw body>".
        assertEquals("t=1,v1=" + hmac("k", "1.b"), Webhooks.sign("k", "b".getBytes(StandardCharsets.UTF_8), 1));
    }

    private static String hmac(String key, String msg) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return net.gaiadesk.e2e.Bytes.toHex(m.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void retryPolicyBacksOffWithJitterAndHonoursRetryAfter() {
        RetryPolicy p = RetryPolicy.of(3, Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5));
        for (int i = 0; i < 50; i++) {
            long first = p.delayMillis(0, null);
            assertTrue(first >= 50 && first <= 100, "min(max, base × 2^n) × 0.5–1.0: " + first);
            long third = p.delayMillis(2, null);
            assertTrue(third >= 200 && third <= 400, "min(max, base × 2^n) × 0.5–1.0: " + third);
        }
        assertEquals(-1, p.delayMillis(3, null), "out of retries");
        assertEquals(700, p.delayMillis(0, 0.7));
        assertEquals(5000, p.delayMillis(0, 5.0), "a Retry-After is waited up to maxRetryWait, not maxDelay");
        assertEquals(-1, p.delayMillis(0, 7.0), "a Retry-After longer than maxRetryWait is not waited for");
        assertEquals(-1, RetryPolicy.none().delayMillis(0, null));
        assertThrows(UsageException.class, () -> RetryPolicy.of(-1, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void cancellationRunsListenersOnceAndAtOnceWhenAlreadyCancelled() {
        Cancellation c = new Cancellation();
        int[] n = {0};
        Cancellation.Registration r = c.onCancel(() -> n[0]++);
        Cancellation.Registration gone = c.onCancel(() -> n[0] += 100);
        gone.close();
        c.cancel();
        c.cancel();
        r.close();
        assertEquals(1, n[0]);
        assertTrue(c.isCancelled());
        c.onCancel(() -> n[0]++);
        assertEquals(2, n[0]);
    }
}
