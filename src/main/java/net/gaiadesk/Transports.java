package net.gaiadesk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import net.gaiadesk.internal.Check;
import net.gaiadesk.internal.HttpEngine;
import net.gaiadesk.internal.JdkHttpEngine;
import net.gaiadesk.internal.LocalConnectors;
import net.gaiadesk.internal.PinnedTrust;
import net.gaiadesk.internal.SocketHttpEngine;
import org.jspecify.annotations.Nullable;

/** How each transport reaches its /v1 API and proves who it is. */
final class Transports {
    private Transports() {}

    static HttpClient defaultClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static Core.Credentials api(String key, @Nullable String deskToken) {
        return callToken -> {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Authorization", "Bearer " + key);
            String t = callToken != null ? callToken : deskToken;
            if (t != null) h.put("X-GaiaDesk-Desk-Token", t);
            return h;
        };
    }

    private static UnreachableException unavailable(String detail, @Nullable Throwable cause) {
        return new UnreachableException(LocalApi.UNAVAILABLE + " (" + detail + ")",
                ErrorDetails.builder().kind("unreachable").reason("local_api_unavailable").exitCode(255).build(), cause);
    }

    static HttpEngine localEngine(String target, boolean windows) {
        return new SocketHttpEngine(() -> {
            try {
                return windows ? LocalConnectors.pipe(target) : LocalConnectors.unix(target);
            } catch (IOException e) {
                throw unavailable(target + ": " + e.getMessage(), e);
            }
        }, "localhost");
    }

    static Core.Credentials local(@Nullable String adminToken, @Nullable String deskToken, String tokenFile) {
        return callToken -> {
            Map<String, String> h = new LinkedHashMap<>();
            String t = callToken != null ? callToken : deskToken;
            if (t != null) {
                h.put("X-GaiaDesk-Desk-Token", t);
                return h;
            }
            String admin = adminToken;
            if (admin == null) {
                try {
                    admin = new String(Files.readAllBytes(Paths.get(tokenFile)), StandardCharsets.UTF_8).trim();
                } catch (NoSuchFileException e) {
                    throw unavailable("no local admin token at " + tokenFile + "; or give an agent token as deskToken", e);
                } catch (IOException e) {
                    throw new GaiaDeskException("cannot read the local admin token " + tokenFile + ": " + e.getMessage(),
                            ErrorDetails.builder().kind("local").reason("local").build(), e);
                }
                if (admin.isEmpty()) {
                    throw new GaiaDeskException("the local admin token file " + tokenFile + " is empty", ErrorDetails.builder().kind("local").reason("local").build());
                }
            }
            h.put("Authorization", "Bearer " + admin);
            return h;
        };
    }

    static HttpEngine lanEngine(PinnedTrust trust, Duration connectTimeout, String origin) {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(trust.context())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkHttpEngine inner = new JdkHttpEngine(client);
        return (call, cancel) -> {
            try {
                return inner.send(call, cancel);
            } catch (IOException e) {
                String actual = trust.lastMismatch();
                if (actual != null && causedBy(e, SSLException.class)) {
                    throw new FingerprintMismatchException("the desk at " + origin + " did not prove the pinned identity: its certificate's SHA-256 is "
                            + (actual.isEmpty() ? "(none)" : actual) + ", not " + trust.pinned()
                            + ". Do not proceed: this may not be your desk. Check the fingerprint in its Settings → GaiaDesk API.",
                            trust.pinned(), actual, ErrorDetails.builder().kind("unreachable").reason("fingerprint_mismatch").exitCode(255).build());
                }
                throw e;
            }
        };
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
        return false;
    }

    static Core.Credentials lan(@Nullable String deskToken) {
        return callToken -> {
            String t = callToken != null ? callToken : deskToken;
            if (t == null) {
                throw Check.usage("the lan transport needs an agent token (deskToken, gdagt_…): a desk's LAN gateway does not take its admin token");
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-GaiaDesk-Desk-Token", t);
            return h;
        };
    }

    static String hostOf(String url) {
        String h = URI.create(url).getRawAuthority();
        return h == null ? url : h;
    }
}
