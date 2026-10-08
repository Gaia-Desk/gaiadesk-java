package net.gaiadesk;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.gaiadesk.e2e.Bytes;

/**
 * Verifying webhook deliveries. Each delivery carries
 * {@code GaiaDesk-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<raw body>">} keyed with the
 * subscription's secret ({@link net.gaiadesk.model.WebhookCreated#getSecret()}). Verify over the raw bytes,
 * reject a {@code t} more than five minutes off, and de-duplicate by {@code GaiaDesk-Event-Id}.
 */
public final class Webhooks {
    /** How far {@code t} may be from now, in seconds. */
    public static final long TOLERANCE_SECONDS = 300;

    private Webhooks() {}

    /** Does {@code header} sign {@code rawBody} with {@code secret}, within five minutes of now? */
    public static boolean verify(String secret, String header, byte[] rawBody) {
        return verify(secret, header, rawBody, System.currentTimeMillis() / 1000);
    }

    /** As {@link #verify(String, String, byte[])}, at a given time (Unix seconds). Constant-time comparison. */
    public static boolean verify(String secret, String header, byte[] rawBody, long nowSeconds) {
        if (secret == null || header == null || rawBody == null) return false;
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i).trim();
            String v = part.substring(i + 1).trim();
            if (k.equals("t")) {
                try {
                    t = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (k.equals("v1")) {
                v1 = v;
            }
        }
        if (t == null || v1 == null || Math.abs(nowSeconds - t) > TOLERANCE_SECONDS) return false;
        byte[] got;
        try {
            got = Bytes.hex(v1);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(got, mac(secret, t, rawBody));
    }

    /** The signature header for a body at a time: what GaiaDesk sends (for tests of your own endpoint). */
    public static String sign(String secret, byte[] rawBody, long timeSeconds) {
        return "t=" + timeSeconds + ",v1=" + Bytes.toHex(mac(secret, timeSeconds, rawBody));
    }

    private static byte[] mac(String secret, long t, byte[] body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            m.update((t + ".").getBytes(StandardCharsets.UTF_8));
            m.update(body);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
