package net.gaiadesk;

import java.util.Locale;
import net.gaiadesk.internal.Check;

/** Helpers for the LAN gateway transport ({@link GaiaDesk#lanBuilder(String, String)}). */
public final class Lan {
    private Lan() {}

    /**
     * A SHA-256 certificate fingerprint as the desk shows it: 32 lowercase hex pairs joined by {@code :}. Takes it
     * with or without colons or spaces, any case, with or without a {@code sha256:} prefix; anything else is a
     * {@link UsageException}.
     */
    public static String normalizeFingerprint(String fp) {
        if (fp == null) throw Check.usage("fingerprint must be a string (the SHA-256 the desk shows, ab:cd:…)");
        String hex = fp.trim().replaceFirst("(?i)^sha-?256[:=\\s]*", "").replaceAll("[:\\s]", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw Check.usage("fingerprint must be the certificate's SHA-256: 32 hex pairs (ab:cd:…), not " + Check.quote(fp));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }
}
