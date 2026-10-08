package net.gaiadesk.e2e;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha): HChaCha20 derives a subkey from the key and the first 16
 * bytes of the 24-byte nonce, then the JDK's IETF ChaCha20-Poly1305 (RFC 8439, Java 11+) runs under that
 * subkey with the nonce {@code 0x00000000 ‖ nonce[16..24]}. Only HChaCha20 is implemented here; the cipher and
 * its tag are the JDK's.
 */
public final class XChaCha20Poly1305 {
    /** The key size. */
    public static final int KEY_BYTES = 32;
    /** The nonce size. */
    public static final int NONCE_BYTES = 24;
    /** The tag size. */
    public static final int TAG_BYTES = 16;

    private XChaCha20Poly1305() {}

    /** Encrypt: the ciphertext followed by its 16-byte tag. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            return cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("XChaCha20-Poly1305 is not available: " + e.getMessage(), e);
        }
    }

    /** Decrypt and authenticate a ciphertext with its tag; a {@link GeneralSecurityException} when it does not authenticate. */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        if (key.length != KEY_BYTES) throw new IllegalArgumentException("the key is 32 bytes");
        if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("the nonce is 24 bytes");
        byte[] subkey = hchacha20(key, Arrays.copyOfRange(nonce, 0, 16));
        byte[] iv = new byte[12];
        System.arraycopy(nonce, 16, iv, 4, 8);
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(mode, new SecretKeySpec(subkey, "ChaCha20"), new IvParameterSpec(iv));
        c.updateAAD(aad);
        Arrays.fill(subkey, (byte) 0);
        return c;
    }

    /** HChaCha20: a 32-byte subkey from a 32-byte key and a 16-byte input. */
    public static byte[] hchacha20(byte[] key, byte[] input) {
        if (key.length != 32 || input.length != 16) throw new IllegalArgumentException("HChaCha20 takes a 32-byte key and 16 bytes");
        int[] s = new int[16];
        s[0] = 0x61707865;
        s[1] = 0x3320646e;
        s[2] = 0x79622d32;
        s[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) s[4 + i] = le32(key, i * 4);
        for (int i = 0; i < 4; i++) s[12 + i] = le32(input, i * 4);
        for (int i = 0; i < 10; i++) {
            quarter(s, 0, 4, 8, 12);
            quarter(s, 1, 5, 9, 13);
            quarter(s, 2, 6, 10, 14);
            quarter(s, 3, 7, 11, 15);
            quarter(s, 0, 5, 10, 15);
            quarter(s, 1, 6, 11, 12);
            quarter(s, 2, 7, 8, 13);
            quarter(s, 3, 4, 9, 14);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            putLe32(out, i * 4, s[i]);
            putLe32(out, 16 + i * 4, s[12 + i]);
        }
        Arrays.fill(s, 0);
        return out;
    }

    private static void quarter(int[] s, int a, int b, int c, int d) {
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 16);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 12);
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 8);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 7);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static void putLe32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }
}
