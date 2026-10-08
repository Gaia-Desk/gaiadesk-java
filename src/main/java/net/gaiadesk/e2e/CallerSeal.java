package net.gaiadesk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import net.gaiadesk.internal.Json;
import org.jspecify.annotations.Nullable;

/**
 * The caller's side of one operation after its request is sealed: its input going up (in order, from 0), the
 * desk's events coming back (opened in order; a gap, a repeat or a change does not open). Not thread-safe:
 * one operation is read by one reader.
 */
public final class CallerSeal {
    private static final List<String> EVENTS = Arrays.asList("stdout", "stderr", "exit", "error");

    private final OpKeys keys;
    private final String desk;
    private final String op;
    private long nextInput;
    private long nextEvent;

    CallerSeal(OpKeys keys, String desk, String op) {
        this.keys = keys;
        this.desk = desk;
        this.op = op;
    }

    /** The desk the operation is sealed to. */
    public String getDesk() { return desk; }

    /** The operation's name ({@code exec}, {@code file_put}, ...). */
    public String getOp() { return op; }

    /** The next piece of input ({@code last} on the final one, which may be empty), with a given nonce. */
    public SealedFrame sealInputWith(byte[] nonce, boolean last, byte[] data) {
        long seq = nextInput++;
        byte[] plain = Bytes.concat(new byte[] {(byte) (last ? 1 : 0)}, data);
        byte[] ct = XChaCha20Poly1305.seal(keys.input, nonce, E2eCrypto.associatedData("input", desk, op, seq), plain);
        return new SealedFrame(seq, Bytes.b64url(nonce), Bytes.b64url(ct));
    }

    /** The next piece of input, with a fresh random nonce. */
    public SealedFrame sealInput(boolean last, byte[] data) {
        return sealInputWith(E2eCrypto.randomBytes(24), last, data);
    }

    /** Open the desk's next event (it must be the next in order): its plaintext. */
    public byte[] openEvent(@Nullable JsonNode frame) {
        SealedFrame f = SealedFrame.fromJson(frame);
        if (f == null) throw new E2eOpenException("e2e_malformed", "a sealed event is malformed");
        return openEvent(f);
    }

    /** Open the desk's next event (it must be the next in order): its plaintext. */
    public byte[] openEvent(SealedFrame f) {
        if (f.getSeq() != nextEvent) {
            throw new E2eOpenException("e2e_decrypt_failed", "a sealed event is out of order (got " + f.getSeq() + ", expected " + nextEvent + ")");
        }
        byte[] plain = E2eCrypto.aeadOpen(keys.event, f.getNonce(), f.getCiphertext(), E2eCrypto.associatedData("event", desk, op, f.getSeq()));
        nextEvent++;
        return plain;
    }

    /** Open the next event as the desk event it carries. */
    public DeskEvent openDeskEvent(@Nullable JsonNode frame) {
        byte[] plain = openEvent(frame);
        JsonNode v;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString();
            v = Json.parse(text);
        } catch (CharacterCodingException | RuntimeException e) {
            throw new E2eOpenException("e2e_malformed", "a sealed event is not JSON");
        }
        if (v == null || !v.isObject() || !EVENTS.contains(v.path("event").asText(""))) {
            throw new E2eOpenException("e2e_malformed", "a sealed event is not a desk event");
        }
        return new DeskEvent((ObjectNode) v);
    }
}
