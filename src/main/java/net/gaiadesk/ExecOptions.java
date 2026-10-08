package net.gaiadesk;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** How a command runs ({@link GaiaDesk#exec}, {@link GaiaDesk#execStream}): an {@code ExecSpec}'s fields. */
public final class ExecOptions extends CallOptions<ExecOptions> {
    @Nullable Shell shell;
    @Nullable Long timeoutSecs;
    @Nullable String cwd;
    @Nullable String stdin;
    @Nullable Map<String, String> env;
    boolean check;

    /** Options with nothing set: the desk's default shell, no stdin, 15 minutes at most. */
    public ExecOptions() {}

    /** The shell it runs in ({@link Shell#POWERSHELL} is sent as {@code pwsh}). */
    public ExecOptions shell(Shell shell) {
        this.shell = shell;
        return this;
    }

    /** Give up after this long ({@code timeout_secs}, whole seconds rounded up; the API holds every call under 15 minutes). */
    public ExecOptions timeout(Duration timeout) {
        this.timeoutSecs = Check.seconds(timeout.toMillis() / 1000.0, "timeout");
        return this;
    }

    /** Give up after this many seconds. */
    public ExecOptions timeoutSeconds(double seconds) {
        this.timeoutSecs = Check.seconds(seconds, "timeout");
        return this;
    }

    /** Give up after this long: {@code 90}, {@code 30s}, {@code 10m}, {@code 1h30m}. */
    public ExecOptions timeout(String duration) {
        this.timeoutSecs = Check.seconds(duration, "timeout");
        return this;
    }

    /** The directory it runs in on the desk (a relative one starts in the desk user's home, or a confined token's folder). */
    public ExecOptions cwd(String cwd) {
        this.cwd = Check.cwd(cwd);
        return this;
    }

    /** Its standard input, as text. */
    public ExecOptions stdin(String stdin) {
        this.stdin = stdin;
        return this;
    }

    /** Its standard input, as UTF-8 bytes (the API takes text). */
    public ExecOptions stdin(byte[] stdin) {
        this.stdin = new String(stdin, StandardCharsets.UTF_8);
        return this;
    }

    /** Environment variables for it ({@code NAME → value}; never logged by the API or the desk). Replaces any set before. */
    public ExecOptions env(Map<String, String> env) {
        this.env = Check.env(env);
        return this;
    }

    /** One environment variable. */
    public ExecOptions env(String name, String value) {
        Map<String, String> m = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        m.put(name, value);
        this.env = Check.env(m);
        return this;
    }

    /** {@code exec} only: a non-zero exit (or a timeout) is a {@link CommandException}. */
    public ExecOptions check(boolean check) {
        this.check = check;
        return this;
    }
}
