package net.gaiadesk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.gaiadesk.ConnectionLostException;
import net.gaiadesk.ErrorDetails;
import net.gaiadesk.GaiaDeskException;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.e2e.CallerSeal;
import net.gaiadesk.e2e.DeskEvent;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.e2e.E2eOpenException;
import org.jspecify.annotations.Nullable;

/**
 * Sealed answers turned back into exactly what the plaintext call answers: JSON results, error envelopes,
 * SSE events (as the server maps a desk's events, protocol {@code desk_op_http.rs}) and file bytes; and an
 * upload's body, sealed.
 */
public final class E2eAnswers {
    private E2eAnswers() {}

    /** A sealed event opened, or the ProtocolException for one that does not. */
    static DeskEvent open(CallerSeal seal, @Nullable JsonNode frame, String op) {
        try {
            return seal.openDeskEvent(frame);
        } catch (E2eOpenException e) {
            throw Errors.protocol("the desk's end-to-end encrypted answer did not open: " + e.getMessage(), op, null, e.getReason());
        }
    }

    private static @Nullable ArrayNode eventsOf(JsonNode json) {
        JsonNode e = json.path("e2e").isObject() ? json.get("e2e")
                : json.path("error").path("e2e").isObject() ? json.get("error").get("e2e") : null;
        return e != null && e.path("events").isArray() ? (ArrayNode) e.get("events") : null;
    }

    /**
     * An error envelope with the desk's real message: a desk's error comes with a placeholder {@code message}
     * and {@code e2e.events}, whose last opens to the {@code error}. An envelope without events is as it is.
     */
    public static JsonNode openErrorEnvelope(JsonNode json, CallerSeal seal, String op) {
        if (!json.isObject() || !json.path("error").isObject()) return json;
        ArrayNode events = eventsOf(json);
        if (events == null) return json;
        ObjectNode error = ((ObjectNode) json.get("error")).deepCopy();
        error.remove("e2e");
        DeskEvent last = null;
        try {
            for (JsonNode f : events) last = open(seal, f, op);
        } catch (GaiaDeskException e) {
            last = null;
        }
        String message = last != null && last.getEvent().equals("error")
                ? last.getMessage()
                : (error.path("message").isTextual() ? error.get("message").asText() : "the desk reported an error") + " (its end-to-end encrypted message did not open)";
        error.put("message", message);
        ObjectNode out = ((ObjectNode) json).deepCopy();
        out.remove("e2e");
        out.set("error", error);
        return out;
    }

    /** A sealed JSON answer ({@code {"e2e": {"events"}}}): the result the plaintext call answers; a held body's envelope, opened. */
    public static JsonNode openAnswer(JsonNode json, CallerSeal seal, String op) {
        if (Errors.envelope(json) != null) return openErrorEnvelope(json, seal, op);
        ArrayNode events = json.isObject() ? eventsOf(json) : null;
        if (events == null || events.size() == 0) {
            throw Errors.protocol("the GaiaDesk API answered an end-to-end encrypted operation without sealed events", op, json, "e2e_unsealed_answer");
        }
        DeskEvent last = null;
        for (JsonNode f : events) last = open(seal, f, op);
        if (last != null && last.getEvent().equals("exit")) return last.getResult();
        if (last != null && last.getEvent().equals("error")) throw deskError(last, seal.getDesk(), op);
        throw Errors.protocol("the desk's sealed answer has no result", op, null, "e2e_malformed");
    }

    /** The HTTP status {@code /v1} gives a desk's error, and the kind it reports. */
    public static Map.Entry<Integer, String> deskErrorStatus(String kind, @Nullable String reason) {
        switch (kind) {
            case "usage": return Map.entry(400, kind);
            case "refused": return Map.entry("desk_busy".equals(reason) ? 429 : "e2e_required".equals(reason) ? 409 : 403, kind);
            case "unreachable": return Map.entry(409, kind);
            case "connection_lost":
            case "protocol": return Map.entry(502, kind);
            default: return Map.entry(422, "failed");
        }
    }

    /** A desk's opened {@code error} event as the error the plaintext call throws. */
    static GaiaDeskException deskError(DeskEvent e, String desk, String op) {
        Map.Entry<Integer, String> s = deskErrorStatus(e.getKind(), e.getReason());
        String kind = s.getValue();
        String reason = e.getReason() != null ? e.getReason() : kind;
        ObjectNode env = Json.object();
        ObjectNode err = env.putObject("error");
        err.put("kind", kind);
        err.put("message", e.getMessage());
        err.put("reason", reason);
        err.put("desk", desk);
        int exit = kind.equals("refused") ? 254 : kind.equals("failed") ? 1 : 255;
        return Errors.forKind(kind, e.getMessage(), ErrorDetails.builder().kind(Errors.sdkKind(kind, reason)).reason(reason).desk(desk)
                .status(s.getKey()).argv(List.of(op)).json(env).exitCode(exit).build());
    }

    /** A sealed download ({@code application/x-ndjson}, one sealed event per line): the file's bytes, written to {@code out}. */
    public static long openDownload(InputStream body, CallerSeal seal, String op, OutputStream out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        long total = 0;
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            if (line.trim().isEmpty()) continue;
            JsonNode frame = Json.tryParse(line);
            if (frame == null) throw Errors.protocol("a sealed download has a line that is not JSON", op, null, "e2e_malformed");
            DeskEvent e = open(seal, frame, op);
            if (e.getEvent().equals("stdout")) {
                byte[] b = Bytes.b64decode(e.getData());
                if (b == null) throw Errors.protocol("a sealed download carries bytes that are not base64", op, null, "e2e_malformed");
                out.write(b);
                total += b.length;
            } else if (e.getEvent().equals("error")) {
                throw deskError(e, seal.getDesk(), op);
            } else if (e.getEvent().equals("exit")) {
                return total;
            }
        }
        throw new ConnectionLostException("the download ended before the desk said it was complete",
                ErrorDetails.builder().kind("connection_lost").reason("incomplete").argv(List.of(op)).desk(seal.getDesk()).exitCode(255).build());
    }

    /** An upload's body, sealed: one input frame per line, at most 48 KiB of the file each, the last flagged. */
    public static byte[] sealUpload(CallerSeal seal, byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        do {
            int n = Math.min(E2eCrypto.INPUT_CHUNK, bytes.length - at);
            byte[] chunk = Arrays.copyOfRange(bytes, at, at + n);
            at += n;
            byte[] line = Bytes.utf8(Json.write(seal.sealInput(at >= bytes.length, chunk).toJson()) + "\n");
            out.write(line, 0, line.length);
        } while (at < bytes.length);
        return out.toByteArray();
    }

    /**
     * A sealed SSE stream as the plaintext one: each {@code sealed} event opened (in order) and mapped as the API
     * maps a desk's events ({@code exec}: stdout, stderr, exit, error; {@code logs}: output, end, interrupted,
     * error), split UTF-8 characters carried. A plaintext {@code error} (the server's: the desk was lost)
     * passes; any other plaintext output in a sealed stream is refused.
     */
    public static final class SseUnsealer {
        private static final List<String> PLAINTEXT = Arrays.asList("stdout", "stderr", "exit", "output", "end", "interrupted", "message");
        private final CallerSeal seal;
        private final boolean logs;
        private final String op;
        private final Utf8Stream out = new Utf8Stream();
        private final Utf8Stream err = new Utf8Stream();

        public SseUnsealer(CallerSeal seal, boolean logs, String op) {
            this.seal = seal;
            this.logs = logs;
            this.op = op;
        }

        private static SseEvent sse(String name, ObjectNode v) {
            return new SseEvent(name, Json.write(v));
        }

        private static ObjectNode ev(String name) {
            ObjectNode o = Json.object();
            o.put("event", name);
            return o;
        }

        public List<SseEvent> map(SseEvent ev) {
            List<SseEvent> res = new ArrayList<>();
            if (ev.event.equals("error")) {
                res.add(ev);
                return res;
            }
            if (!ev.event.equals("sealed")) {
                if (PLAINTEXT.contains(ev.event)) {
                    throw Errors.protocol("the GaiaDesk API sent a plaintext `" + ev.event + "` event in an end-to-end encrypted stream", op, null, "e2e_unsealed_answer");
                }
                return res;
            }
            JsonNode frame = Json.tryParse(ev.data);
            // Its data names it too ("event": "sealed"), as every /v1 SSE event's does.
            if (frame != null && frame.isObject() && frame.has("event") && !"sealed".equals(frame.get("event").asText())) frame = null;
            DeskEvent e = open(seal, frame, op);
            switch (e.getEvent()) {
                case "stdout":
                case "stderr": {
                    byte[] b = Bytes.b64decode(e.getData());
                    if (b == null) return res; // not base64: the desk's bug, dropped (as the server does)
                    boolean isErr = !logs && e.getEvent().equals("stderr");
                    String text = (isErr ? err : out).decode(b);
                    if (!text.isEmpty()) {
                        String name = logs ? "output" : e.getEvent();
                        res.add(sse(name, ev(name).put("data", text)));
                    }
                    return res;
                }
                case "exit": {
                    JsonNode r = e.getResult();
                    ObjectNode result = r.isObject() ? ((ObjectNode) r).deepCopy() : Json.object();
                    if (!logs) {
                        String o = out.flush();
                        if (!o.isEmpty()) res.add(sse("stdout", ev("stdout").put("data", o)));
                        String x = err.flush();
                        if (!x.isEmpty()) res.add(sse("stderr", ev("stderr").put("data", x)));
                        ObjectNode exit = Json.object();
                        Iterator<Map.Entry<String, JsonNode>> it = result.fields();
                        while (it.hasNext()) {
                            Map.Entry<String, JsonNode> f = it.next();
                            if (!f.getKey().equals("stdout") && !f.getKey().equals("stderr") && !f.getKey().equals("truncated")) exit.set(f.getKey(), f.getValue());
                        }
                        exit.put("event", "exit");
                        res.add(sse("exit", exit));
                    } else {
                        String o = out.flush();
                        if (!o.isEmpty()) res.add(sse("output", ev("output").put("data", o)));
                        if (result.path("interrupted").asBoolean(false)) res.add(sse("interrupted", ev("interrupted")));
                        else {
                            ObjectNode end = ev("end");
                            if (result.has("job")) end.set("job", result.get("job"));
                            res.add(sse("end", end));
                        }
                    }
                    return res;
                }
                case "error": {
                    ObjectNode error = Json.object();
                    error.put("kind", e.getKind());
                    error.put("message", e.getMessage());
                    error.put("desk", seal.getDesk());
                    if (e.getReason() != null) error.put("reason", e.getReason());
                    ObjectNode o = ev("error");
                    if (!logs) o.put("exit", e.getKind().equals("refused") ? 254 : 255);
                    o.set("error", error);
                    res.add(sse("error", o));
                    return res;
                }
                default:
                    return res;
            }
        }
    }
}
