package net.gaiadesk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.gaiadesk.Cancellation;
import net.gaiadesk.E2eException;
import net.gaiadesk.E2eMode;
import net.gaiadesk.ErrorDetails;
import net.gaiadesk.GaiaDeskException;
import net.gaiadesk.RefusedException;
import net.gaiadesk.e2e.E2eCrypto;
import net.gaiadesk.e2e.SealedOperation;
import org.jspecify.annotations.Nullable;

/**
 * End-to-end encryption on the hosted API: whether and to which key an operation is sealed (the desk's
 * {@code e2e_pub} from {@code GET /desks/{id}}, cached; pinned keys; the mode; a wake when a desk that must
 * be sealed to lists no key), and the one retry each for {@code e2e_required} and {@code e2e_decrypt_failed}.
 */
public final class E2eLayer {
    /** How the layer makes its own calls (the desk lookup, a wake). */
    public interface ApiJson {
        JsonNode call(String method, String path, @Nullable String deskToken, @Nullable JsonNode json, Cancellation cancel);
    }

    /** One try of the operation: sealed, or in the clear when given null. */
    public interface Attempt<T> {
        T run(@Nullable SealedOperation sealed);
    }

    private static final long KEY_TTL_MS = 5 * 60_000L;
    private static final long NO_KEY_TTL_MS = 30_000L;
    private static final int DEFAULT_WAKE_S = 30;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final class Info {
        final byte @Nullable [] pub;
        final boolean required;
        final String why;

        Info(byte @Nullable [] pub, boolean required, String why) {
            this.pub = pub;
            this.required = required;
            this.why = why;
        }
    }

    private static final class Cached {
        final long at;
        final Info info;

        Cached(long at, Info info) {
            this.at = at;
            this.info = info;
        }
    }

    private final E2eMode mode;
    private final Map<String, byte[]> pins;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Consumer<String> warn;
    private final ApiJson api;
    private final String baseUrl;

    public E2eLayer(E2eMode mode, Map<String, byte[]> pins, Consumer<String> warn, ApiJson api, String baseUrl) {
        this.mode = mode;
        this.pins = new ConcurrentHashMap<>(pins);
        this.warn = warn;
        this.api = api;
        this.baseUrl = baseUrl;
    }

    public E2eMode mode() {
        return mode;
    }

    /** Forget what the lookup said about {@code desk} (its key may have rotated). */
    public void forget(String desk) {
        cache.remove(desk);
    }

    private static String path(String desk) {
        return "/desks/" + Urls.encode(desk);
    }

    private Info info(String desk, @Nullable String deskToken, Cancellation c, boolean fresh) {
        Cached hit = cache.get(desk);
        long now = System.currentTimeMillis();
        if (!fresh && hit != null && now - hit.at < (hit.info.pub != null ? KEY_TTL_MS : NO_KEY_TTL_MS)) return hit.info;
        JsonNode d;
        try {
            d = api.call("GET", path(desk), deskToken, null, c);
        } catch (GaiaDeskException e) {
            if (e.getKind().equals("interrupted")) throw e;
            return new Info(null, false, "its key could not be read (GET /desks/" + desk + ": " + e.getMessage() + ")");
        }
        JsonNode o = d.isObject() ? d : Json.object();
        byte[] pub = E2eCrypto.deskKey(Json.text(o, "e2e_pub"));
        boolean required = o.path("e2e_required").asBoolean(false) && o.path("e2e_required").isBoolean();
        String why = pub != null ? ""
                : o.path("online").isBoolean() && !o.path("online").asBoolean()
                        ? "it is offline, and lists its key only while online"
                        : "it lists no end-to-end key (a GaiaDesk from before end-to-end encryption?)";
        Info info = new Info(pub, required, why);
        cache.put(desk, new Cached(now, info));
        return info;
    }

    /** The server's key for {@code desk}, refused when a pinned key differs. */
    private byte @Nullable [] checked(String desk, Info info) {
        byte[] pin = pins.get(desk);
        if (info.pub != null && pin != null && !Arrays.equals(info.pub, pin)) {
            forget(desk);
            throw new E2eException("the GaiaDesk API lists a different end-to-end key for desk " + desk + " than the pinned one (e2eKeys); nothing was sent",
                    ErrorDetails.builder().kind("refused").reason("e2e_key_mismatch").desk(desk).exitCode(254).build());
        }
        return info.pub != null ? info.pub : pin;
    }

    /**
     * The key to seal {@code desk}'s next operation to, or null to send it in the clear (AUTO, no key: warned
     * once). {@code insist}: it must be sealed (the API said {@code e2e_required}). A desk that must be sealed
     * to and lists no key is woken and asked again; still none is an E2eException.
     */
    public byte @Nullable [] key(String desk, @Nullable String deskToken, @Nullable Integer wake, Cancellation c, boolean insist) {
        Info info = info(desk, deskToken, c, insist);
        byte[] pub = checked(desk, info);
        if (pub != null) return pub;
        if (mode != E2eMode.REQUIRE && !info.required && !insist) {
            if (WARNED.add(baseUrl + " " + desk)) {
                warn.accept("GaiaDesk: operations on desk " + desk + " are not end-to-end encrypted: " + info.why
                        + ". The API relays them in the clear (use E2eMode.REQUIRE to refuse that).");
            }
            return null;
        }
        ObjectNode body = Json.object();
        body.put("wait_s", Math.min(90, wake != null ? wake : DEFAULT_WAKE_S));
        try {
            api.call("POST", path(desk) + "/wake", deskToken, body, c);
        } catch (GaiaDeskException e) {
            if (e.getKind().equals("interrupted")) throw e;
        }
        info = info(desk, deskToken, c, true);
        byte[] woke = checked(desk, info);
        if (woke != null) return woke;
        throw new E2eException("desk " + desk + " must be reached end-to-end encrypted, but " + info.why + "; nothing was sent",
                ErrorDetails.builder().kind("refused").reason("e2e_unavailable").desk(desk).exitCode(254).build());
    }

    /**
     * Run one desk operation. A plaintext call refused {@code e2e_required} is sealed and sent again; a sealed one
     * the desk could not open ({@code e2e_decrypt_failed}: its key rotated) is sealed to the key read again, once.
     */
    public <T> T call(String desk, String op, ObjectNode request, @Nullable String deskToken, @Nullable Integer wake, Cancellation c, Attempt<T> attempt) {
        if (mode == E2eMode.OFF) return attempt.run(null);
        byte[] pub = key(desk, deskToken, wake, c, false);
        try {
            return attempt.run(pub != null ? E2eCrypto.sealRequest(pub, desk, op, request.deepCopy()) : null);
        } catch (RefusedException e) {
            if (pub == null && "e2e_required".equals(e.getReason())) {
                forget(desk);
                byte[] k = key(desk, deskToken, wake, c, true);
                return attempt.run(E2eCrypto.sealRequest(java.util.Objects.requireNonNull(k), desk, op, request.deepCopy()));
            }
            if (pub != null && !(e instanceof E2eException) && "e2e_decrypt_failed".equals(e.getReason())) {
                forget(desk);
                byte[] again = key(desk, deskToken, wake, c, false);
                if (again == null) throw e;
                return attempt.run(E2eCrypto.sealRequest(again, desk, op, request.deepCopy()));
            }
            throw e;
        }
    }
}
