package net.gaiadesk.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.gaiadesk.internal.Json;
import org.junit.jupiter.api.Test;

/**
 * The protocol's fixed test vectors byte for byte (protocol/src/e2e/vectors.json, copied to
 * src/test/resources/e2e-vectors.json), the XChaCha20-Poly1305 draft's own vectors, round trips, and every way
 * a message must fail to open.
 */
class E2eCryptoTest {
    static final JsonNode V = load();
    static final byte[] DESK_SECRET = Bytes.hex(V.get("desk_secret_hex").asText());

    static JsonNode load() {
        try (InputStream in = E2eCryptoTest.class.getResourceAsStream("/e2e-vectors.json")) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] b(String s) {
        return Bytes.b64decode(s);
    }

    static String s(JsonNode n, String f) {
        return n.get(f).asText();
    }

    static SealedOperation vectorSeal() {
        byte[] deskPub = E2eCrypto.x25519Public(DESK_SECRET);
        return E2eCrypto.sealRequestWith(Bytes.hex(s(V, "eph_secret_hex")), b(s(V.get("request"), "nonce")), deskPub, s(V, "desk_id"), s(V, "op"),
                Bytes.utf8(s(V, "request_plaintext")));
    }

    static SealedFrame frame(JsonNode n) {
        return SealedFrame.fromJson(n);
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void vectorsTheDeskKeyTheSealedRequestAndItsHeaderByteForByte() {
        assertEquals(s(V, "desk_pub"), Bytes.b64url(E2eCrypto.x25519Public(DESK_SECRET)));
        SealedRequest r = vectorSeal().getRequest();
        assertEquals(V.get("request"), r.toJson());
        assertEquals(s(V, "request_header"), E2eCrypto.requestHeader(r));
    }

    @Test
    void vectorsAssociatedData() {
        assertEquals(s(V, "aad_request_hex"), Bytes.toHex(E2eCrypto.associatedData("request", s(V, "desk_id"), s(V, "op"))));
        assertEquals(s(V, "aad_event_1_hex"), Bytes.toHex(E2eCrypto.associatedData("event", s(V, "desk_id"), s(V, "op"), 1L)));
    }

    @Test
    void vectorsTheEventsOpenAndTheInputFramesSealToTheSameCiphertext() {
        CallerSeal seal = vectorSeal().getSeal();
        for (JsonNode e : V.get("events")) assertEquals(s(e, "plaintext"), text(seal.openEvent(e)));
        for (JsonNode i : V.get("inputs")) {
            SealedFrame f = seal.sealInputWith(b(s(i, "nonce")), i.get("last").asBoolean(), Bytes.utf8(s(i, "data")));
            assertEquals(new SealedFrame(i.get("seq").asLong(), s(i, "nonce"), s(i, "ciphertext")), f);
        }
        CallerSeal again = vectorSeal().getSeal();
        DeskEvent e0 = again.openDeskEvent(V.get("events").get(0));
        assertEquals("stdout", e0.getEvent());
        assertEquals("dmVjdG9yCg==", e0.getData());
        DeskEvent e1 = again.openDeskEvent(V.get("events").get(1));
        assertEquals("exit", e1.getEvent());
        assertEquals(0, e1.getResult().get("exit").asInt());
    }

    @Test
    void vectorsTheDeskSideOpensTheRequestAndTheInputFrames() {
        DeskSeal desk = DeskSeal.open(DESK_SECRET, s(V, "desk_id"), s(V, "op"), SealedRequest.fromJson(V.get("request")));
        assertEquals(s(V, "request_plaintext"), text(desk.plain()));
        DeskSeal.Input i0 = desk.openInput(frame(V.get("inputs").get(0)));
        assertEquals("hello ", text(i0.data));
        assertTrue(!i0.last);
        DeskSeal.Input i1 = desk.openInput(frame(V.get("inputs").get(1)));
        assertEquals("world", text(i1.data));
        assertTrue(i1.last);
        JsonNode e0 = V.get("events").get(0);
        assertEquals(frame(e0), desk.sealEventWith(b(s(e0, "nonce")), Bytes.utf8(s(e0, "plaintext"))));
    }

    @Test
    void xchacha20Poly1305DraftVectors() {
        // draft-irtf-cfrg-xchacha-03 §2.2.1 (HChaCha20) and §A.3.1 (AEAD).
        byte[] sub = XChaCha20Poly1305.hchacha20(Bytes.hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                Bytes.hex("000000090000004a0000000031415927"));
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", Bytes.toHex(sub));
        byte[] pt = Bytes.utf8("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.");
        byte[] ct = XChaCha20Poly1305.seal(Bytes.hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"),
                Bytes.hex("404142434445464748494a4b4c4d4e4f5051525354555657"), Bytes.hex("50515253c0c1c2c3c4c5c6c7"), pt);
        assertEquals("bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52e"
                + "c0875924c1c7987947deafd8780acf49", Bytes.toHex(ct));
    }

    @Test
    void roundTripWithFreshKeys() {
        byte[] deskPub = E2eCrypto.x25519Public(DESK_SECRET);
        ObjectNode req = Json.object().put("op", "file_put").put("path", "/tmp/x").put("size", 3);
        SealedOperation sealed = E2eCrypto.sealRequest(deskPub, "123456789", "file_put", req);
        DeskSeal desk = DeskSeal.open(DESK_SECRET, "123456789", "file_put", sealed.getRequest());
        JsonNode inner = Json.parse(text(desk.plain()));
        assertEquals(1, inner.get("v").asInt());
        assertTrue(Math.abs(inner.get("ts").asLong() - System.currentTimeMillis() / 1000) < 5);
        assertEquals(req, inner.get("request"));
        DeskSeal.Input in = desk.openInput(sealed.getSeal().sealInput(true, Bytes.utf8("abc")));
        assertTrue(in.last);
        assertEquals("abc", text(in.data));
        ObjectNode ev = Json.object().put("event", "stdout").put("data", Bytes.b64encode(Bytes.utf8("é")));
        assertEquals(ev, sealed.getSeal().openDeskEvent(desk.sealEvent(ev).toJson()).toJson());
        SealedOperation other = E2eCrypto.sealRequest(deskPub, "123456789", "exec", Json.object().put("op", "exec"));
        assertNotEquals(other.getRequest().getPub(), sealed.getRequest().getPub(), "a fresh ephemeral key per operation");
        assertNotEquals(other.getRequest().getNonce(), sealed.getRequest().getNonce());
    }

    static String flip(String s, int at) {
        byte[] u = Bytes.b64decode(s);
        u[at] ^= 1;
        return Bytes.b64url(u);
    }

    @Test
    void tamperingAFlippedBitInTheCiphertextNonceOrKeyDoesNotOpen() {
        SealedRequest r = vectorSeal().getRequest();
        for (SealedRequest bad : new SealedRequest[] {
                new SealedRequest(1, r.getPub(), r.getNonce(), flip(r.getCiphertext(), 5)),
                new SealedRequest(1, r.getPub(), flip(r.getNonce(), 0), r.getCiphertext()),
                new SealedRequest(1, flip(r.getPub(), 3), r.getNonce(), r.getCiphertext())}) {
            assertThrows(E2eOpenException.class, () -> DeskSeal.open(DESK_SECRET, s(V, "desk_id"), s(V, "op"), bad));
        }
        JsonNode e0 = V.get("events").get(0);
        E2eOpenException x = assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(new SealedFrame(0, s(e0, "nonce"), flip(s(e0, "ciphertext"), 2))));
        assertEquals("e2e_decrypt_failed", x.getReason());
        x = assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(new SealedFrame(0, flip(s(e0, "nonce"), 23), s(e0, "ciphertext"))));
        assertEquals("e2e_decrypt_failed", x.getReason());
        x = assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(new SealedFrame(0, s(e0, "nonce"), "AAAA")));
        assertEquals("e2e_malformed", x.getReason());
        x = assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(Json.object().put("seq", 0)));
        assertEquals("e2e_malformed", x.getReason());
    }

    @Test
    void associatedDataAnotherDeskOrOperationDoesNotOpen() {
        SealedRequest r = vectorSeal().getRequest();
        assertEquals("e2e_decrypt_failed", assertThrows(E2eOpenException.class, () -> DeskSeal.open(DESK_SECRET, "481902775", s(V, "op"), r)).getReason());
        assertEquals("e2e_decrypt_failed", assertThrows(E2eOpenException.class, () -> DeskSeal.open(DESK_SECRET, s(V, "desk_id"), "job_start", r)).getReason());
        byte[] deskPub = E2eCrypto.x25519Public(DESK_SECRET);
        SealedOperation elsewhere = E2eCrypto.sealRequestWith(Bytes.hex(s(V, "eph_secret_hex")), b(s(V.get("request"), "nonce")), deskPub, "481902775", s(V, "op"),
                Bytes.utf8(s(V, "request_plaintext")));
        assertThrows(E2eOpenException.class, () -> elsewhere.getSeal().openEvent(V.get("events").get(0)));
        SealedOperation otherOp = E2eCrypto.sealRequestWith(Bytes.hex(s(V, "eph_secret_hex")), b(s(V.get("request"), "nonce")), deskPub, s(V, "desk_id"), "stats",
                Bytes.utf8(s(V, "request_plaintext")));
        assertThrows(E2eOpenException.class, () -> otherOp.getSeal().openEvent(V.get("events").get(0)));
    }

    @Test
    void eventFramesReorderedReplayedRenumberedOrSkippedDoNotOpen() {
        JsonNode e0 = V.get("events").get(0);
        JsonNode e1 = V.get("events").get(1);
        assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(e1), "the second first");
        CallerSeal a = vectorSeal().getSeal();
        a.openEvent(e0);
        assertThrows(E2eOpenException.class, () -> a.openEvent(e0), "replayed");
        assertThrows(E2eOpenException.class, () -> vectorSeal().getSeal().openEvent(new SealedFrame(0, s(e1, "nonce"), s(e1, "ciphertext"))), "renumbered");
        CallerSeal c = vectorSeal().getSeal();
        c.openEvent(e0);
        assertThrows(E2eOpenException.class, () -> c.openEvent(new SealedFrame(1, s(e0, "nonce"), s(e0, "ciphertext"))), "a replay renumbered");
        CallerSeal d = vectorSeal().getSeal();
        d.openEvent(e0);
        assertEquals(s(e1, "plaintext"), text(d.openEvent(e1)), "in order it opens");
        DeskSeal desk = DeskSeal.open(DESK_SECRET, s(V, "desk_id"), s(V, "op"), SealedRequest.fromJson(V.get("request")));
        JsonNode i1 = V.get("inputs").get(1);
        assertThrows(RuntimeException.class, () -> desk.openInput(new SealedFrame(0, s(i1, "nonce"), s(i1, "ciphertext"))));
    }

    @Test
    void keysALowOrderKeyGivesNoSharedSecretDeskKeyReads32Base64urlBytesOnly() {
        assertEquals("e2e_weak_key", assertThrows(E2eOpenException.class, () -> E2eCrypto.x25519(Bytes.hex(s(V, "eph_secret_hex")), new byte[32])).getReason());
        assertEquals(32, E2eCrypto.deskKey(s(V, "desk_pub")).length);
        assertEquals(32, E2eCrypto.deskKey(s(V, "desk_pub") + "=").length, "padding tolerated");
        assertNull(E2eCrypto.deskKey("AAAA"));
        assertNull(E2eCrypto.deskKey("not base64!"));
        assertNull(E2eCrypto.deskKey(null));
    }

    @Test
    void base64StandardWithPaddingUrlSafeWithout() {
        for (String x : new String[] {"", "f", "fo", "foo", "foob", "fooba", "foobar"}) {
            byte[] raw = Bytes.utf8(x);
            assertEquals(Base64.getEncoder().encodeToString(raw), Bytes.b64encode(raw));
            assertArrayEquals(raw, Bytes.b64decode(Bytes.b64encode(raw)));
            assertArrayEquals(raw, Bytes.b64decode(Bytes.b64url(raw)));
            assertTrue(!Bytes.b64url(raw).contains("="));
        }
        assertArrayEquals(new byte[] {(byte) 0xfb, (byte) 0xff}, Bytes.b64decode("-_8"));
        assertNull(Bytes.b64decode("a"));
        assertNull(Bytes.b64decode("a*b"));
    }

    @Test
    void hkdfRfc5869TestCase1() {
        byte[] okm = E2eCrypto.hkdf(Bytes.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Bytes.hex("000102030405060708090a0b0c"),
                Bytes.hex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Bytes.toHex(okm));
    }
}
