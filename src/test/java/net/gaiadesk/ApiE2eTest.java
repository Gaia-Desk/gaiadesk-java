package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.model.CopyResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end encryption on the API transport, against a mock API that is also the desk: every operation sealed
 * gives exactly what it gives in the clear, the API never sees the command, env, stdin, path or file bytes, and
 * the modes, pins and retries behave.
 */
class ApiE2eTest {
    static final String CANARY = "canary-7f3a9";
    static final byte[] KEY = Bytes.hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    static final byte[] KEY2 = Bytes.hex("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40");
    static final String PUB = Bytes.b64url(E2eCrypto.x25519Public(KEY));
    static final String PUB2 = Bytes.b64url(E2eCrypto.x25519Public(KEY2));
    static final String SEALED = "111111111";
    static final String OLD = "222222222";
    static final String MUST = "333333333";
    static final String ASLEEP = "444444444";
    static final String GONE = "555555555";
    static final String STALE = "666666666";
    static final String ROTATED = "777777777";

    static MockApi api;
    static final List<String> warnings = new ArrayList<>();
    static GaiaDesk sealedGd;
    static GaiaDesk plainGd;

    @BeforeAll
    static void start() throws Exception {
        api = new MockApi();
        api.desks.put(SEALED, new MockApi.Desk().secret(KEY));
        api.desks.put(OLD, new MockApi.Desk());
        api.desks.put(MUST, new MockApi.Desk().secret(KEY).required(true));
        api.desks.put(ASLEEP, new MockApi.Desk().secret(KEY).required(true).online(false).wakeable(true));
        api.desks.put(GONE, new MockApi.Desk().secret(KEY).online(false));
        api.desks.put(STALE, new MockApi.Desk().secret(KEY).required(true).hideKeyLookups(1));
        api.desks.put(ROTATED, new MockApi.Desk().secret(KEY));
        sealedGd = gd().build();
        plainGd = gd().e2e(E2eMode.OFF).build();
    }

    @AfterAll
    static void stop() {
        api.close();
    }

    static GaiaDesk.Builder gd() {
        return GaiaDesk.builder().apiKey("ak_test").deskToken("gdagt_test").baseUrl(api.url).onWarning(warnings::add);
    }

    static GaiaDesk.Builder owner() {
        return GaiaDesk.builder().apiKey("session-person").baseUrl(api.url).onWarning(m -> {});
    }

    /** Run f, and assert the API saw nothing of the canary in any request it made. */
    static <T> T blind(Supplier<T> f) {
        int before = api.requests.size();
        T r = f.get();
        List<MockApi.Recorded> seen = api.since(before);
        assertFalse(seen.isEmpty());
        for (MockApi.Recorded q : seen) assertFalse(q.raw().contains(CANARY), "the API saw the canary in " + q.method + " " + q.path);
        return r;
    }

    /** The same call sealed (the API seeing none of it) and in the clear: equal answers. */
    static <T> T same(Function<GaiaDesk, T> f) {
        int before = api.sealed.size();
        T sealed = blind(() -> f.apply(sealedGd));
        assertTrue(api.sealed.size() > before, "it went sealed");
        T plain = f.apply(plainGd);
        if (sealed instanceof byte[]) assertArrayEquals((byte[]) sealed, (byte[]) plain);
        else assertEquals(plain, sealed);
        return sealed;
    }

    static final class Shape {
        final String cls;
        final String kind;
        final String reason;
        final Integer status;
        final String message;
        final String desk;
        final Integer exit;

        Shape(GaiaDeskException e) {
            cls = e.getClass().getSimpleName();
            kind = e.getKind();
            reason = e.getReason();
            status = e.getStatus();
            message = e.getMessage();
            desk = e.getDesk();
            exit = e.getExitCode();
        }

        List<Object> list() {
            return java.util.Arrays.asList(cls, kind, reason, status, message, desk, exit);
        }
    }

    static Shape sameError(Function<GaiaDesk, Object> f) {
        Shape sealed = blind(() -> new Shape(assertThrows(GaiaDeskException.class, () -> f.apply(sealedGd))));
        Shape plain = new Shape(assertThrows(GaiaDeskException.class, () -> f.apply(plainGd)));
        assertEquals(plain.list(), sealed.list());
        return sealed;
    }

    static String drainShape(StreamResult r) {
        return r.getStdout() + "|" + r.getStderr() + "|" + r.getExit().getExitCode() + "|" + (r.getExit().getError() == null ? "" : r.getExit().getError().toString());
    }

    @Test
    void execSealedInAPostBodyTheSameResultAsInTheClearTheKeyLookedUpOnce() {
        long lookups0 = lookups(SEALED);
        var r = same(g -> g.exec(SEALED, "echo " + CANARY, new ExecOptions().env("SECRET", CANARY).stdin(CANARY).cwd("/srv/" + CANARY).timeoutSeconds(30)));
        assertEquals("ran: echo " + CANARY + " é\nenv: SECRET=" + CANARY + "\nstdin: " + CANARY + "\n", r.getStdout());
        long n = lookups(SEALED);
        assertTrue(n > lookups0);
        sealedGd.exec(SEALED, "again");
        assertEquals(n, lookups(SEALED), "cached");
        JsonNode body = api.requests.stream().filter(q -> q.path.equals("/v1/desks/" + SEALED + "/exec")).reduce((a, b) -> b).orElseThrow().json();
        assertEquals(List.of("e2e"), iter(body.fieldNames()));
        assertEquals(List.of("v", "pub", "nonce", "ciphertext"), iter(body.get("e2e").fieldNames()));
    }

    static List<String> iter(java.util.Iterator<String> it) {
        List<String> l = new ArrayList<>();
        it.forEachRemaining(l::add);
        return l;
    }

    static long lookups(String desk) {
        return api.requests.stream().filter(q -> q.method.equals("GET") && q.path.equals("/v1/desks/" + desk)).count();
    }

    @Test
    void execStreamSealedEventsOpenIntoTheSameChunksAndExit() {
        String r = same(g -> drainShape(g.execStream(SEALED, "echo " + CANARY, new ExecOptions().env("K", CANARY)).collect()));
        assertEquals("ran: echo " + CANARY + " é\nenv: K=" + CANARY + "\n|warn\n|0|", r);
        String lost = same(g -> drainShape(g.execStream(SEALED, "lose").collect()));
        assertTrue(lost.contains("connection_lost"));
        String admin = same(g -> drainShape(g.execStream(SEALED, "as-admin").collect()));
        assertTrue(admin.contains("admin_not_via_api"), admin);
    }

    @Test
    void jobsLogsWaitKillStatsTheSameAnswersSealed() {
        same(g -> g.runJob(SEALED, "build", "make " + CANARY, new JobOptions().env("CI", CANARY).shell(Shell.BASH).cwd(CANARY)));
        same(g -> g.jobs(SEALED));
        assertEquals("tail 10\n", same(g -> g.jobLogs(SEALED, "build", new LogsOptions().tail(10))));
        assertEquals("line1\nline2 é\n|||", same(g -> drainShape(g.followJobLogs(SEALED, "build").collect())).replace("|0|", "||"));
        assertEquals(Integer.valueOf(3), same(g -> g.waitJob(SEALED, "build", new WaitOptions().timeoutSeconds(60))).getJob().getExitCode());
        assertEquals("held", same(g -> g.waitJob(SEALED, "held")).getJob().getName());
        same(g -> g.killJob(SEALED, "build"));
        same(g -> g.stats(SEALED));
        MockApi.Recorded tail = api.requests.stream().filter(q -> q.path.endsWith("/logs") && q.header("gaiadesk-e2e") != null).findFirst().orElseThrow();
        assertFalse(tail.query.containsKey("tail"), "tail travels inside the sealed request");
    }

    @Test
    void deskErrorsThePlaceholderBecomesTheDesksOwnMessage() {
        Shape e = sameError(g -> g.exec(SEALED, "refuse"));
        assertEquals(List.of("RefusedException", "refused", "token_refused", 403), e.list().subList(0, 4));
        assertEquals(SEALED, e.desk);
        assertTrue(e.message.contains("no exec scope"));
        Shape w = sameError(g -> g.waitJob(SEALED, "held-gone"));
        assertEquals("OperationFailedException", w.cls);
        assertTrue(w.message.contains("no job named \"held-gone\""));
        assertEquals("no job named \"missing\"", sameError(g -> g.jobLogs(SEALED, "missing")).message);
        assertTrue(same(g -> drainShape(g.followJobLogs(SEALED, "missing").collect())).contains("no job named"));
        RefusedException admin = assertThrows(RefusedException.class, () -> sealedGd.exec(SEALED, "as-admin"));
        assertEquals(Reasons.ADMIN_NOT_VIA_API, admin.getReason());
    }

    @Test
    void filesUploadAsSealedFramesDownloadAsSealedEvents() {
        byte[] big = new byte[150 * 1024];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i % 251);
        byte[] marked = (CANARY + " file\n").getBytes(StandardCharsets.UTF_8);
        CopyResult up = same(g -> g.uploadBytes(marked, SEALED, "docs/" + CANARY + ".txt"));
        assertEquals(marked.length, up.getBytes());
        MockApi.Recorded put = api.requests.stream().filter(q -> q.method.equals("PUT") && q.header("gaiadesk-e2e") != null).reduce((a, b) -> b).orElseThrow();
        assertEquals("application/x-ndjson", put.header("content-type"));
        assertEquals(Map.of(), put.query, "the path travels sealed");
        blind(() -> sealedGd.uploadBytes(big, SEALED, "big.bin"));
        String[] frames = api.last().body().trim().split("\n");
        assertEquals(4, frames.length, "48 KiB per frame");
        assertArrayEquals(big, blind(() -> sealedGd.downloadBytes(SEALED, "big.bin")));
        assertArrayEquals(marked, same(g -> g.downloadBytes(SEALED, "docs/" + CANARY + ".txt")));
        sameError(g -> g.downloadBytes(SEALED, "missing"));
        ConnectionLostException t = assertThrows(ConnectionLostException.class, () -> sealedGd.downloadBytes(SEALED, "truncated"));
        assertEquals("incomplete", t.getReason());
    }

    @Test
    void tokensMintListRevokeSealed() {
        GaiaDesk sealed = owner().build();
        GaiaDesk clear = owner().e2e(E2eMode.OFF).build();
        int before = (int) api.sealed.stream().filter(o -> o.startsWith("token_")).count();
        var minted = blind(() -> sealed.createToken(new TokenSpec(SEALED).name("bot-" + CANARY).expires("1h")));
        assertEquals(clear.createToken(new TokenSpec(SEALED).name("bot-" + CANARY).expires("1h")), minted);
        assertEquals("bot-" + CANARY, minted.getTokens().get(0).getToken().getLabel());
        assertEquals(clear.listTokens(SEALED), sealed.listTokens(SEALED));
        assertEquals(clear.revokeToken(SEALED, "tok1"), sealed.revokeToken(SEALED, "tok1"));
        assertEquals(before + 3, api.sealed.stream().filter(o -> o.startsWith("token_")).count());
    }

    @Test
    void autoNoKeyInTheClearWarnedOncePerDesk() {
        List<String> seen = new ArrayList<>();
        GaiaDesk g = gd().onWarning(seen::add).build();
        int before = api.plain.size();
        g.stats(OLD);
        g.stats(OLD);
        assertEquals(before + 2, api.plain.size());
        long mine = java.util.stream.Stream.concat(seen.stream(), warnings.stream()).filter(w -> w.contains(OLD)).count();
        assertEquals(1, mine);
        assertTrue(java.util.stream.Stream.concat(seen.stream(), warnings.stream()).filter(w -> w.contains(OLD)).findFirst().get().contains("not end-to-end encrypted"));
    }

    @Test
    void requireNeverInTheClearADeskWithoutAKeyIsWokenThenE2eException() {
        int before = api.requests.size();
        E2eException e = assertThrows(E2eException.class, () -> gd().e2e(E2eMode.REQUIRE).build().exec(OLD, "echo " + CANARY));
        assertInstanceOf(RefusedException.class, e);
        assertEquals("e2e_unavailable", e.getReason());
        assertEquals(OLD, e.getDesk());
        assertTrue(assertThrows(E2eException.class, () -> gd().e2e(E2eMode.REQUIRE).build().stats(GONE)).getMessage().contains("offline"));
        for (MockApi.Recorded q : api.since(before)) assertTrue(q.path.endsWith("/wake") || q.path.matches("/v1/desks/\\d+"), "only lookups and wakes: " + q.path);
        assertTrue(api.wakes.contains(OLD) && api.wakes.contains(GONE));
        assertEquals(0, blind(() -> gd().e2e(E2eMode.REQUIRE).build().exec(ASLEEP, "echo " + CANARY)).getExit());
        assertTrue(api.wakes.contains(ASLEEP));
    }

    @Test
    void aDeskThatRequiresItSealedInAutoAPlaintextCallRefusedIsSealedAndRetriedOnce() {
        int before = api.sealed.size();
        gd().build().stats(MUST);
        assertEquals(before + 1, api.sealed.size());
        long n = api.requests.stream().filter(q -> q.path.equals("/v1/desks/" + STALE + "/stats")).count();
        assertEquals(5.0, gd().build().stats(STALE).getCpuPercent());
        assertEquals(n + 2, api.requests.stream().filter(q -> q.path.equals("/v1/desks/" + STALE + "/stats")).count(), "refused once, then sealed");
        assertEquals("stats", api.sealed.get(api.sealed.size() - 1));
        RefusedException off = assertThrows(RefusedException.class, () -> gd().e2e(E2eMode.OFF).build().stats(MUST));
        assertEquals("e2e_required", off.getReason());
        assertEquals(Integer.valueOf(409), off.getStatus());
    }

    @Test
    void aRotatedKeyIsFetchedAgainAndTheCallSealedAgainOnce() {
        GaiaDesk g = gd().build();
        g.stats(ROTATED);
        api.desks.get(ROTATED).secret = KEY2;
        long n = api.requests.stream().filter(q -> q.path.equals("/v1/desks/" + ROTATED + "/stats")).count();
        assertEquals(5.0, g.stats(ROTATED).getCpuPercent());
        assertEquals(n + 2, api.requests.stream().filter(q -> q.path.equals("/v1/desks/" + ROTATED + "/stats")).count());
    }

    @Test
    void pinnedKeysAMismatchIsRefusedBeforeAnythingIsSentThePinSealsWhileNoKeyIsListed() {
        int before = api.requests.size();
        E2eException e = assertThrows(E2eException.class, () -> gd().e2eKey(SEALED, PUB2).build().exec(SEALED, "echo " + CANARY));
        assertEquals("e2e_key_mismatch", e.getReason());
        for (MockApi.Recorded q : api.since(before)) assertEquals("/v1/desks/" + SEALED, q.path, "only the lookup");
        assertEquals(5.0, gd().e2eKey(SEALED, PUB).build().stats(SEALED).getCpuPercent());
        assertEquals(0, blind(() -> gd().e2e(E2eMode.REQUIRE).e2eKeys(Map.of(GONE, PUB)).build().exec(GONE, "echo " + CANARY)).getExit());
    }

    @Test
    void aHostileServerAlteredEventsOrAPlaintextAnswerToASealedCallAreRefused() {
        api.tamper = "flip";
        try {
            assertEquals("e2e_decrypt_failed", assertThrows(ProtocolException.class, () -> sealedGd.stats(SEALED)).getReason());
            assertEquals("protocol", sealedGd.execStream(SEALED, "x").collect().getExit().getError().getKind());
            RefusedException r = assertThrows(RefusedException.class, () -> sealedGd.exec(SEALED, "refuse"));
            assertTrue(r.getMessage().contains("did not open"), "the placeholder, said to be unopened");
            api.tamper = "plaintext";
            assertEquals("e2e_unsealed_answer", assertThrows(ProtocolException.class, () -> sealedGd.stats(SEALED)).getReason());
            StreamResult p = sealedGd.execStream(SEALED, "x").collect();
            assertEquals("", p.getStdout(), "no forged output");
            assertEquals("protocol", p.getExit().getError().getKind());
        } finally {
            api.tamper = null;
        }
        assertNotNull(sealedGd.stats(SEALED), "honest again, fine again");
    }
}
