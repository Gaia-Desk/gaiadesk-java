package net.gaiadesk;

import java.util.Locale;
import java.util.Map;
import net.gaiadesk.internal.LocalConnectors;

/**
 * Where the desk's local API is ({@link GaiaDesk#localBuilder()}): the Unix socket
 * {@code $GAIADESK_API_DIR/api.sock} (when that is an absolute directory), else {@code ~/.gaiadesk/api.sock};
 * on Windows the named pipe {@code $GAIADESK_API_PIPE}, else {@code \\.\pipe\gaiadesk-api-<user>}; and the
 * desk's local admin token ({@code gdlocal_…}) in {@code api-token} beside the socket.
 */
public final class LocalApi {
    /** What a missing socket or pipe means, as the error says it. */
    public static final String UNAVAILABLE = "GaiaDesk is not serving its local API here: is the app running, and is Settings → GaiaDesk API → Local API on?";

    private LocalApi() {}

    /** Can this Java reach the local API? Windows: always (the pipe). macOS and Linux: Java 16 or later (Unix domain sockets). */
    public static boolean isSupported() {
        return isWindows() || LocalConnectors.unixSockets();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The pipe-name form of a user name: lowercased, {@code [a-z0-9._-]} kept, the rest {@code _}, at most 64 characters, {@code user} if empty. */
    public static String pipeUser(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "user" : s;
    }

    /** The Windows pipe: {@code $GAIADESK_API_PIPE}, else {@code \\.\pipe\gaiadesk-api-<user>} ({@code $USERNAME}, else {@code username}). */
    public static String pipeName(Map<String, String> env, String username) {
        String p = env.get("GAIADESK_API_PIPE");
        if (p != null && !p.isEmpty()) return p;
        String u = env.get("USERNAME");
        return "\\\\.\\pipe\\gaiadesk-api-" + pipeUser(u != null && !u.isEmpty() ? u : username);
    }

    private static boolean isAbsolute(String p, boolean windows) {
        return windows ? p.matches("^(?:[a-zA-Z]:[\\\\/]|[\\\\/]{2}).*") : p.startsWith("/");
    }

    private static String join(String dir, String name, boolean windows) {
        return dir.replaceAll("[\\\\/]+$", "") + (windows ? "\\" : "/") + name;
    }

    /** The directory of the socket and token: {@code $GAIADESK_API_DIR} when it is absolute, else {@code <home>/.gaiadesk}. */
    public static String apiDir(Map<String, String> env, String home, boolean windows) {
        String d = env.get("GAIADESK_API_DIR");
        if (d != null && !d.isEmpty() && isAbsolute(d, windows)) return d;
        return join(home, ".gaiadesk", windows);
    }

    /** The Unix socket (macOS, Linux). */
    public static String socketPath(Map<String, String> env, String home, boolean windows) {
        return join(apiDir(env, home, windows), "api.sock", windows);
    }

    /** The file holding the desk's local admin token. */
    public static String tokenPath(Map<String, String> env, String home, boolean windows) {
        return join(apiDir(env, home, windows), "api-token", windows);
    }
}
