package net.gaiadesk.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Percent-encoding as {@code encodeURIComponent} does it. */
public final class Urls {
    private Urls() {}

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }
}
