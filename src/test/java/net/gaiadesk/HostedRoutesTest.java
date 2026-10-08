package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.internal.Json;
import net.gaiadesk.model.AuditEvent;
import net.gaiadesk.model.DeskDetail;
import net.gaiadesk.model.DeviceList;
import net.gaiadesk.model.ReachLog;
import net.gaiadesk.model.SupportSession;
import net.gaiadesk.model.SupportSessionCreated;
import net.gaiadesk.model.WakeResult;
import net.gaiadesk.model.WebhookCreated;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The hosted API's own routes: fleet, reach, wake, audit (paged), webhooks and support sessions. */
class HostedRoutesTest {
    static final byte[] KEY = Bytes.hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    static MockApi api;
    static GaiaDesk gd;

    @BeforeAll
    static void start() throws Exception {
        api = new MockApi();
        api.desks.put("123456789", new MockApi.Desk().secret(KEY));
        api.desks.put("987654321", new MockApi.Desk().online(false).wakeable(true));
        api.desks.put("111111111", new MockApi.Desk().online(false));
        gd = GaiaDesk.builder().apiKey("ak_test").baseUrl(api.url).onWarning(m -> {}).build();
    }

    @AfterAll
    static void stop() {
        api.close();
    }

    @Test
    void desksTheListOneDeskWithItsKeyAndWakeHints() {
        DeviceList list = gd.devices();
        assertEquals(3, list.getDevices().size());
        assertEquals("api_key", list.getIdentity().getSource());
        assertEquals(List.of("server"), list.getSources());
        DeskDetail d = gd.device("123456789");
        assertEquals(Bytes.b64url(E2eCrypto.x25519Public(KEY)), d.getE2ePub());
        assertTrue(d.getFeatures().contains("desk_op_e2e"));
        assertEquals(Boolean.FALSE, d.getE2eRequired());
        assertEquals(0, d.getWake().getDoorbellSockets());
        DeskDetail off = gd.device("111111111");
        assertEquals(Boolean.FALSE, off.getOnline());
        assertEquals("silent", off.getOfflineReason());
        assertNull(off.getE2ePub());
        assertEquals("/v1/desks/111111111", api.last().path);
        assertTrue(gd.device("987654321").getWake().isLanWake());
    }

    @Test
    void reachSinceAndLimit() {
        ReachLog log = gd.reach("123456789", new ReachQuery().since(Instant.ofEpochSecond(1790000000L)).limit(50));
        assertEquals(Map.of("since", "1790000000", "limit", "50"), api.last().query);
        assertEquals(1790000000L, log.getSince());
        assertEquals("silent", log.getEvents().get(0).getReason());
        assertEquals("0.10.325", log.getEvents().get(1).getVersion());
        assertThrows(UsageException.class, () -> new ReachQuery().limit(1001));
    }

    @Test
    void wakeRingsAndWaitsAndSaysWhetherItWoke() {
        WakeResult w = gd.wake("987654321", new WakeOptions().waitSeconds(30).idempotencyKey("wake-1"));
        assertEquals("POST", api.last().method);
        assertEquals(Json.parse("{\"wait_s\":30}"), api.last().json());
        assertEquals("wake-1", api.last().header("idempotency-key"));
        assertTrue(w.isWoke());
        assertEquals(1, w.getRang().getDoorbell());
        UnreachableException none = assertThrows(UnreachableException.class, () -> gd.wake("111111111"));
        assertEquals("no_wake_path", none.getReason());
        assertEquals(Integer.valueOf(409), none.getStatus());
    }

    @Test
    void auditFiltersAndEveryPageWithoutLossOrRepeats() {
        List<AuditEvent> page = gd.audit(new AuditQuery().desk("123456789").action("api.*").actor("you@example.com").token("ak_1")
                .since(Instant.ofEpochMilli(1)).limit(5));
        assertEquals(5, page.size());
        assertEquals(Map.of("desk", "123456789", "action", "api.*", "actor", "you@example.com", "token", "ak_1", "since_ms", "1", "limit", "5"), api.last().query);
        assertEquals("api_key", page.get(0).getActor().getType());
        assertEquals("ok", page.get(0).getMetadata().get("result").asText());
        Set<String> ids = new HashSet<>();
        List<AuditEvent> all = new ArrayList<>();
        for (AuditEvent e : gd.auditAll(new AuditQuery().limit(40))) {
            all.add(e);
            assertTrue(ids.add(e.getId()), "no repeats: " + e.getId());
        }
        assertEquals(250, all.size(), "every event, two per millisecond across page boundaries");
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1).getOccurredAtMs() >= all.get(i).getOccurredAtMs(), "newest first");
        assertThrows(UsageException.class, () -> new AuditQuery().limit(501));
    }

    @Test
    void webhooksCreateListDelete() {
        WebhookCreated w = gd.createWebhook(new WebhookSpec("https://example.com/hooks", "desk.online", "desk.offline").description("ops"));
        assertEquals(Json.parse("{\"url\":\"https://example.com/hooks\",\"events\":[\"desk.online\",\"desk.offline\"],\"description\":\"ops\"}"), api.last().json());
        assertTrue(w.getSecret().startsWith("whsec_"));
        assertTrue(w.getId().matches("wh_[0-9a-f]{16}"));
        assertTrue(gd.webhooks().stream().anyMatch(x -> x.getId().equals(w.getId())));
        assertFalse(gd.webhooks().get(0).getOther().containsKey("secret"), "never the secret again");
        assertEquals(w.getId(), gd.deleteWebhook(w.getId()));
        assertEquals("DELETE", api.last().method);
        assertThrows(UnreachableException.class, () -> gd.deleteWebhook(w.getId()));
    }

    @Test
    void supportSessionsCreateListGet() {
        SupportSessionCreated s = gd.createSupportSession(new SupportSessionSpec().mode(SupportMode.COBROWSE).customer("name", "Ada Lovelace")
                .customer("vip", true).expiresIn(java.time.Duration.ofMinutes(30)).origin("https://app.example.com"));
        assertEquals(Json.parse("{\"mode\":\"cobrowse\",\"customer\":{\"name\":\"Ada Lovelace\",\"vip\":true},\"expires_in\":1800,\"origin\":\"https://app.example.com\"}"),
                api.last().json());
        assertTrue(s.getEmbedToken().startsWith("gdemb_"));
        assertEquals("waiting", s.getState());
        assertEquals("Ada Lovelace", s.getCustomer().get("name"));
        List<SupportSession> open = gd.supportSessions(new SupportSessionQuery().all().limit(10));
        assertEquals(Map.of("state", "all", "limit", "10"), api.last().query);
        assertEquals(s.getId(), open.get(0).getId());
        assertEquals("123456789", gd.supportSession(s.getId()).getJoinCode());
        assertEquals("unknown_support_session", assertThrows(UnreachableException.class, () -> gd.supportSession("ss_0000000000000000")).getReason());
        assertThrows(UsageException.class, () -> new SupportSessionSpec().customer("x", List.of()));
    }
}
