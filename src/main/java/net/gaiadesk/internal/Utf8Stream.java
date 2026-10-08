package net.gaiadesk.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** UTF-8 decoding of bytes that arrive in pieces: a character split across pieces waits for its end (TextDecoder's {@code stream: true}). */
public final class Utf8Stream {
    private final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer pending = ByteBuffer.allocate(0);

    /** The text these bytes complete. */
    public String decode(byte[] bytes) {
        ByteBuffer in = ByteBuffer.allocate(pending.remaining() + bytes.length);
        in.put(pending).put(bytes).flip();
        CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
        CoderResult r = dec.decode(in, out, false);
        if (r.isError()) throw new IllegalStateException(r.toString());
        pending = ByteBuffer.allocate(in.remaining());
        pending.put(in).flip();
        out.flip();
        return out.toString();
    }

    /** The end: what is left (an unfinished character as U+FFFD). */
    public String flush() {
        CharBuffer out = CharBuffer.allocate(pending.remaining() + 2);
        dec.decode(pending, out, true);
        dec.flush(out);
        dec.reset();
        pending = ByteBuffer.allocate(0);
        out.flip();
        return out.toString();
    }
}
