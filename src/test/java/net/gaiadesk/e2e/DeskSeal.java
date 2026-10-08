package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import net.gaiadesk.internal.Json;

/**
 * A desk's side of end-to-end encrypted desk operations, for the tests only: open a sealed request with the
 * desk's static secret, seal events back, open input frames. Built from the SDK's primitives in the order the
 * protocol's crypto.rs gives, so a mock API can act as a desk.
 */
public final class DeskSeal {
    private final OpKeys keys;
    private final String desk;
    private final String op;
    private long nextInput;
    private long nextEvent;
    private final byte[] plain;

    private DeskSeal(OpKeys keys, String desk, String op, byte[] plain) {
        this.keys = keys;
        this.desk = desk;
        this.op = op;
        this.plain = plain;
    }

    /** The opened request's plaintext. */
    public byte[] plain() {
        return plain.clone();
    }

    /** Open a sealed request to {@code desk} as {@code op} with the desk's secret. */
    public static DeskSeal open(byte[] deskSecret, String desk, String op, SealedRequest req) {
        if (req.getV() != 1) throw new IllegalArgumentException("bad version");
        byte[] ephPub = Bytes.b64decode(req.getPub());
        if (ephPub == null || ephPub.length != 32) throw new E2eOpenException("e2e_malformed", "bad pub");
        byte[] deskPub = E2eCrypto.x25519Public(deskSecret);
        OpKeys keys = E2eCrypto.deriveKeys(E2eCrypto.x25519(deskSecret, ephPub), ephPub, deskPub);
        byte[] plain = E2eCrypto.aeadOpen(keys.request, req.getNonce(), req.getCiphertext(), E2eCrypto.associatedData("request", desk, op));
        return new DeskSeal(keys, desk, op, plain);
    }

    public SealedFrame sealEventWith(byte[] nonce, byte[] plaintext) {
        long seq = nextEvent++;
        byte[] ct = E2eCrypto.aeadSeal(keys.event, nonce, E2eCrypto.associatedData("event", desk, op, seq), plaintext);
        return new SealedFrame(seq, Bytes.b64url(nonce), Bytes.b64url(ct));
    }

    public SealedFrame sealEvent(JsonNode event) {
        return sealEventWith(E2eCrypto.randomBytes(24), Bytes.utf8(Json.write(event)));
    }

    /** The caller's next input frame. */
    public Input openInput(SealedFrame f) {
        if (f.getSeq() != nextInput) throw new E2eOpenException("e2e_decrypt_failed", "input out of order");
        byte[] p = E2eCrypto.aeadOpen(keys.input, f.getNonce(), f.getCiphertext(), E2eCrypto.associatedData("input", desk, op, f.getSeq()));
        nextInput++;
        if (p[0] != 0 && p[0] != 1) throw new E2eOpenException("e2e_malformed", "bad input flag");
        return new Input(p[0] == 1, Arrays.copyOfRange(p, 1, p.length));
    }

    /** An opened input frame. */
    public static final class Input {
        public final boolean last;
        public final byte[] data;

        Input(boolean last, byte[] data) {
            this.last = last;
            this.data = data;
        }
    }
}
