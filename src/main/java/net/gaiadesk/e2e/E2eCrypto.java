package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/**
 * End-to-end encrypted desk operations, v1 (the API reference's "End-to-end encryption"; the protocol crate's
 * {@code e2e.rs}, whose fixed test vectors this reproduces).
 *
 * <p>Per operation: an ephemeral X25519 key pair; {@code shared = X25519(eph, desk)};
 * {@code prk = HKDF-SHA256-Extract("gaiadesk desk-op e2e v1", shared)}; one key per use ({@code request},
 * {@code input}, {@code event}) {@code = HKDF-Expand(prk, label ‖ 0x00 ‖ eph_pub ‖ desk_pub, 32)}. Every message
 * is XChaCha20-Poly1305 with a random 24-byte nonce and associated data naming the use, the desk, the
 * operation and (input and events) the message's place.
 */
public final class E2eCrypto {
    /** The feature a desk lists when it opens sealed operations. */
    public static final String FEATURE = "desk_op_e2e";
    /** The protocol version. */
    public static final int VERSION = 1;
    /** The HTTP header that carries a sealed request on a call without a JSON body. */
    public static final String HEADER = "GaiaDesk-E2E";
    /** The content type of sealed frames, one per line. */
    public static final String FRAMES_CONTENT_TYPE = "application/x-ndjson";
    /** The HKDF salt. */
    public static final String HKDF_SALT = "gaiadesk desk-op e2e v1";
    /** The most file bytes one sealed input frame carries. */
    public static final int INPUT_CHUNK = 48 * 1024;

    // RFC 8410 DER wrappers for a raw X25519 key.
    private static final byte[] PKCS8_PREFIX = Bytes.hex("302e020100300506032b656e04220420");
    private static final byte[] SPKI_PREFIX = Bytes.hex("302a300506032b656e032100");
    private static final byte[] BASE_POINT = basePoint();
    private static final SecureRandom RANDOM = new SecureRandom();

    private E2eCrypto() {}

    private static byte[] basePoint() {
        byte[] b = new byte[32];
        b[0] = 9;
        return b;
    }

    /** {@code n} random bytes. */
    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] agree(byte[] secret, byte[] pub) throws GeneralSecurityException {
        if (secret.length != 32 || pub.length != 32) throw new IllegalArgumentException("X25519 keys are 32 bytes");
        KeyFactory kf = KeyFactory.getInstance("XDH");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Bytes.concat(PKCS8_PREFIX, secret)));
        PublicKey p = kf.generatePublic(new X509EncodedKeySpec(Bytes.concat(SPKI_PREFIX, pub)));
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(priv);
        ka.doPhase(p, true);
        return ka.generateSecret();
    }

    /** The X25519 public key of a 32-byte secret. */
    public static byte[] x25519Public(byte[] secret) {
        try {
            return agree(secret, BASE_POINT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 is not available: " + e.getMessage(), e);
        }
    }

    /** X25519, refusing a non-contributory (all-zero) result ({@code e2e_weak_key}). */
    public static byte[] x25519(byte[] secret, byte[] pub) {
        byte[] shared;
        try {
            shared = agree(secret, pub);
        } catch (GeneralSecurityException | IllegalStateException e) {
            // The JDK refuses a small-order point itself.
            throw new E2eOpenException("e2e_weak_key", "the key exchange gave no shared secret (a low-order key)");
        }
        int acc = 0;
        for (byte b : shared) acc |= b;
        if (acc == 0) throw new E2eOpenException("e2e_weak_key", "the key exchange gave no shared secret (a low-order key)");
        return shared;
    }

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
            for (byte[] p : parts) mac.update(p);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869): {@code length} bytes (at most 255 × 32) from the input key material, salt and info. */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length < 0 || length > 255 * 32) throw new IllegalArgumentException("HKDF length");
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int at = 0;
        for (int i = 1; at < length; i++) {
            t = hmac(prk, t, info, new byte[] {(byte) i});
            int n = Math.min(t.length, length - at);
            System.arraycopy(t, 0, out, at, n);
            at += n;
        }
        return out;
    }

    /** The associated data: {@code "gaiadesk-e2e/v1 <use>" 0 desk 0 op}, then {@code 0 seq} (u64 big-endian) for input and events. */
    public static byte[] associatedData(String use, String desk, String op, @Nullable Long seq) {
        byte[] head = Bytes.concat(Bytes.utf8("gaiadesk-e2e/v1 " + use), new byte[] {0}, Bytes.utf8(desk), new byte[] {0}, Bytes.utf8(op));
        if (seq == null) return head;
        return Bytes.concat(head, new byte[] {0}, ByteBuffer.allocate(8).putLong(seq).array());
    }

    /** The associated data of a request (no place). */
    public static byte[] associatedData(String use, String desk, String op) {
        return associatedData(use, desk, op, null);
    }

    /** The keys from the exchange's shared secret and both public keys. */
    public static OpKeys deriveKeys(byte[] shared, byte[] ephPub, byte[] deskPub) {
        byte[] salt = Bytes.utf8(HKDF_SALT);
        return new OpKeys(
                hkdf(shared, salt, Bytes.concat(Bytes.utf8("request"), new byte[] {0}, ephPub, deskPub), 32),
                hkdf(shared, salt, Bytes.concat(Bytes.utf8("input"), new byte[] {0}, ephPub, deskPub), 32),
                hkdf(shared, salt, Bytes.concat(Bytes.utf8("event"), new byte[] {0}, ephPub, deskPub), 32));
    }

    /** XChaCha20-Poly1305 encryption: the ciphertext and its tag. */
    public static byte[] aeadSeal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        return XChaCha20Poly1305.seal(key, nonce, aad, plaintext);
    }

    /** XChaCha20-Poly1305 decryption of base64url fields; an {@link E2eOpenException} when it does not authenticate. */
    public static byte[] aeadOpen(byte[] key, String nonce, String ciphertext, byte[] aad) {
        byte[] n = Bytes.b64decode(nonce);
        byte[] c = Bytes.b64decode(ciphertext);
        if (n == null || n.length != 24 || c == null || c.length < 16) throw new E2eOpenException("e2e_malformed", "a sealed message is malformed");
        try {
            return XChaCha20Poly1305.open(key, n, aad, c);
        } catch (GeneralSecurityException e) {
            throw new E2eOpenException("e2e_decrypt_failed", "a sealed message did not open: it was altered, reordered, or sealed for another desk or operation");
        }
    }

    /**
     * Seal {@code plaintext} (the inner request's JSON) for desk {@code desk} (its key {@code deskPub}) as
     * operation {@code op}, with a given ephemeral secret and nonce: the test vectors' entry point. Never reuse either.
     */
    public static SealedOperation sealRequestWith(byte[] eph, byte[] nonce, byte[] deskPub, String desk, String op, byte[] plaintext) {
        byte[] ephPub = x25519Public(eph);
        OpKeys keys = deriveKeys(x25519(eph, deskPub), ephPub, deskPub);
        byte[] ct = aeadSeal(keys.request, nonce, associatedData("request", desk, op), plaintext);
        SealedRequest r = new SealedRequest(VERSION, Bytes.b64url(ephPub), Bytes.b64url(nonce), Bytes.b64url(ct));
        return new SealedOperation(r, new CallerSeal(keys, desk, op));
    }

    /** Seal a desk operation's request ({@code {"op": …}}) now: {@code {"v":1,"ts":<now>,"request":…}} under a fresh ephemeral key. */
    public static SealedOperation sealRequest(byte[] deskPub, String desk, String op, ObjectNode request) {
        return sealRequest(deskPub, desk, op, request, System.currentTimeMillis());
    }

    /** As {@link #sealRequest(byte[], String, String, ObjectNode)}, at a given time (Unix milliseconds). */
    public static SealedOperation sealRequest(byte[] deskPub, String desk, String op, ObjectNode request, long nowMs) {
        ObjectNode inner = Json.object();
        inner.put("v", VERSION);
        inner.put("ts", Math.floorDiv(nowMs, 1000L));
        inner.set("request", request);
        return sealRequestWith(randomBytes(32), randomBytes(24), deskPub, desk, op, Bytes.utf8(Json.write(inner)));
    }

    /** The {@code GaiaDesk-E2E} header value of a sealed request: base64url of its JSON. */
    public static String requestHeader(SealedRequest r) {
        return Bytes.b64url(Bytes.utf8(Json.write(r.toJson())));
    }

    /** A desk key as given ({@code e2e_pub}, base64url): its 32 bytes, or null. */
    public static byte @Nullable [] deskKey(@Nullable String s) {
        if (s == null) return null;
        byte[] k = Bytes.b64decode(s);
        return k != null && k.length == 32 ? k : null;
    }
}
