package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.gaiadesk.internal.Check;
import net.gaiadesk.internal.Errors;
import net.gaiadesk.internal.Json;
import net.gaiadesk.internal.Urls;
import net.gaiadesk.model.AuditEvent;
import net.gaiadesk.model.DeskDetail;
import net.gaiadesk.model.DeviceList;
import net.gaiadesk.model.ReachLog;
import net.gaiadesk.model.SupportSession;
import net.gaiadesk.model.SupportSessionCreated;
import net.gaiadesk.model.WakeResult;
import net.gaiadesk.model.Webhook;
import net.gaiadesk.model.WebhookCreated;
import org.jspecify.annotations.Nullable;

/** The fleet, audit, webhook and support routes (the hosted API's own; {@code GET /desks} everywhere). */
final class HostedOps {
    private final Core core;

    HostedOps(Core core) {
        this.core = core;
    }

    private JsonNode get(String path, Calls.Scope s, @Nullable CallOptions<?> o, Map<String, Object> query) {
        Core.Req r = DeskOps.req("GET", path, s.cancel, o);
        for (Map.Entry<String, Object> q : query.entrySet()) r.query(q.getKey(), q.getValue());
        return core.json(r);
    }

    DeviceList devices(@Nullable RequestOptions o) {
        try (Calls.Scope s = Calls.scope(o)) {
            JsonNode json = get("/desks", s, o, Map.of());
            if (!json.path("devices").isArray()) throw Errors.protocol("the GaiaDesk API listed no devices", "GET /desks", json, null);
            return DeskOps.convert(json, DeviceList.class, "GET /desks");
        }
    }

    DeskDetail device(String deskId, @Nullable RequestOptions o) {
        core.hostedOnly("device()");
        String path = DeskOps.deskPath(Check.desk(deskId));
        try (Calls.Scope s = Calls.scope(o)) {
            return DeskOps.convert(get(path, s, o, Map.of()), DeskDetail.class, "GET " + path);
        }
    }

    ReachLog reach(String deskId, @Nullable ReachQuery q) {
        core.hostedOnly("reach()");
        String path = DeskOps.deskPath(Check.desk(deskId)) + "/reach";
        try (Calls.Scope s = Calls.scope(q)) {
            Core.Req r = DeskOps.req("GET", path, s.cancel, q);
            if (q != null) r.query("since", q.since).query("limit", q.limit);
            return DeskOps.convert(core.json(r), ReachLog.class, "GET " + path);
        }
    }

    WakeResult wake(String deskId, @Nullable WakeOptions o) {
        core.hostedOnly("wake()");
        String path = DeskOps.deskPath(Check.desk(deskId)) + "/wake";
        try (Calls.Scope s = Calls.scope(o)) {
            Core.Req r = DeskOps.req("POST", path, s.cancel, o);
            ObjectNode body = Json.object();
            if (o != null && o.waitSeconds != null) body.put("wait_s", o.waitSeconds);
            r.json = body;
            return DeskOps.convert(core.json(r), WakeResult.class, "POST " + path);
        }
    }

    List<AuditEvent> audit(@Nullable AuditQuery q) {
        return audit(q, null);
    }

    private List<AuditEvent> audit(@Nullable AuditQuery q, @Nullable Long untilMs) {
        core.hostedOnly("audit()");
        try (Calls.Scope s = Calls.scope(q)) {
            Core.Req r = DeskOps.req("GET", "/audit", s.cancel, q);
            if (q != null) {
                r.query("desk", q.desk).query("actor", q.actor).query("action", q.action).query("token", q.token)
                        .query("since_ms", q.sinceMs).query("until_ms", untilMs != null ? untilMs : q.untilMs).query("limit", q.limit);
            } else if (untilMs != null) {
                r.query("until_ms", untilMs);
            }
            return DeskOps.listOf(core.json(r), "events", AuditEvent.class, "GET /audit");
        }
    }

    /** Every matching event, newest first, page after page (each next page ends where the last one did; repeats dropped). */
    Iterable<AuditEvent> auditAll(@Nullable AuditQuery q) {
        core.hostedOnly("auditAll()");
        int limit = q != null && q.limit != null ? q.limit : 100;
        return () -> new Iterator<AuditEvent>() {
            private final Set<String> seen = new HashSet<>();
            private List<AuditEvent> page = new ArrayList<>();
            private int at;
            private @Nullable Long until = q == null ? null : q.untilMs;
            private boolean last;

            @Override
            public boolean hasNext() {
                while (at >= page.size()) {
                    if (last) return false;
                    List<AuditEvent> got = audit(q, until);
                    List<AuditEvent> fresh = new ArrayList<>();
                    for (AuditEvent e : got) if (seen.add(e.getId())) fresh.add(e);
                    if (got.size() < limit || fresh.isEmpty()) last = true;
                    if (!got.isEmpty()) until = got.get(got.size() - 1).getOccurredAtMs();
                    page = fresh;
                    at = 0;
                }
                return true;
            }

            @Override
            public AuditEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                return page.get(at++);
            }
        };
    }

    List<Webhook> webhooks(@Nullable RequestOptions o) {
        core.hostedOnly("webhooks()");
        try (Calls.Scope s = Calls.scope(o)) {
            return DeskOps.listOf(get("/webhooks", s, o, Map.of()), "webhooks", Webhook.class, "GET /webhooks");
        }
    }

    WebhookCreated createWebhook(WebhookSpec spec) {
        core.hostedOnly("createWebhook()");
        ObjectNode body = Json.object();
        body.put("url", spec.url);
        ArrayNode ev = body.putArray("events");
        for (String e : spec.events) ev.add(e);
        if (spec.description != null) body.put("description", spec.description);
        try (Calls.Scope s = Calls.scope(spec)) {
            Core.Req r = DeskOps.req("POST", "/webhooks", s.cancel, spec);
            r.json = body;
            return DeskOps.convert(core.json(r), WebhookCreated.class, "POST /webhooks");
        }
    }

    String deleteWebhook(String id, @Nullable RequestOptions o) {
        core.hostedOnly("deleteWebhook()");
        if (id == null || id.trim().isEmpty()) throw Check.usage("a webhook id (wh_…) is required");
        String path = "/webhooks/" + Urls.encode(id);
        try (Calls.Scope s = Calls.scope(o)) {
            JsonNode json = core.json(DeskOps.req("DELETE", path, s.cancel, o));
            return json.path("deleted").isTextual() ? json.get("deleted").asText() : id;
        }
    }

    SupportSessionCreated createSupportSession(@Nullable SupportSessionSpec spec) {
        core.hostedOnly("createSupportSession()");
        ObjectNode body = Json.object();
        if (spec != null) {
            if (spec.mode != null) body.put("mode", spec.mode.wire());
            if (spec.customer != null) body.set("customer", Json.tree(spec.customer));
            if (spec.expiresIn != null) body.put("expires_in", spec.expiresIn);
            if (spec.origin != null) body.put("origin", spec.origin);
        }
        try (Calls.Scope s = Calls.scope(spec)) {
            Core.Req r = DeskOps.req("POST", "/support/sessions", s.cancel, spec);
            r.json = body;
            return DeskOps.convert(core.json(r), SupportSessionCreated.class, "POST /support/sessions");
        }
    }

    List<SupportSession> supportSessions(@Nullable SupportSessionQuery q) {
        core.hostedOnly("supportSessions()");
        try (Calls.Scope s = Calls.scope(q)) {
            Core.Req r = DeskOps.req("GET", "/support/sessions", s.cancel, q);
            if (q != null) r.query("state", q.state).query("limit", q.limit);
            return DeskOps.listOf(core.json(r), "sessions", SupportSession.class, "GET /support/sessions");
        }
    }

    SupportSession supportSession(String id, @Nullable RequestOptions o) {
        core.hostedOnly("supportSession()");
        if (id == null || id.trim().isEmpty()) throw Check.usage("a support session id (ss_…) is required");
        String path = "/support/sessions/" + Urls.encode(id);
        try (Calls.Scope s = Calls.scope(o)) {
            return DeskOps.convert(core.json(DeskOps.req("GET", path, s.cancel, o)), SupportSession.class, "GET " + path);
        }
    }
}
