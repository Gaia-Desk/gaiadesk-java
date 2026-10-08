package net.gaiadesk.e2e;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/** Byte helpers the protocol uses: base64 (standard and url-safe), hex, UTF-8, concatenation. */
public final class Bytes {
    private Bytes() {}

    /** UTF-8 bytes of a string. */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The parts, one after another. */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** Hex digits (any case, even length) as bytes; an {@link IllegalArgumentException} otherwise. */
    public static byte[] hex(String s) {
        if (s.length() % 2 != 0 || !s.matches("[0-9a-fA-F]*")) throw new IllegalArgumentException("not hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** Lowercase hex. */
    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** Standard base64 with padding (a desk event's {@code data}). */
    public static String b64encode(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** base64url without padding: every binary field of the envelope. */
    public static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Standard or url-safe base64, padded or not; null when it is not base64. */
    public static byte @Nullable [] b64decode(String s) {
        String t = s.trim().replace('-', '+').replace('_', '/');
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == '=') end--;
        t = t.substring(0, end);
        if (!t.matches("[A-Za-z0-9+/]*") || t.length() % 4 == 1) return null;
        StringBuilder padded = new StringBuilder(t);
        while (padded.length() % 4 != 0) padded.append('=');
        try {
            return Base64.getDecoder().decode(padded.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
