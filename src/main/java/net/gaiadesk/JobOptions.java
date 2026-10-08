package net.gaiadesk;

import java.util.LinkedHashMap;
import java.util.Map;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** How a background job runs ({@link GaiaDesk#runJob}): a {@code JobSpec}'s fields. */
public final class JobOptions extends CallOptions<JobOptions> {
    @Nullable Priority priority;
    @Nullable Integer cpu;
    @Nullable Long memMb;
    @Nullable Boolean keepAwake;
    @Nullable String cwd;
    @Nullable Shell shell;
    @Nullable Map<String, String> env;

    /** Options with nothing set: the desk's defaults. */
    public JobOptions() {}

    /** Its priority. */
    public JobOptions priority(Priority priority) {
        this.priority = priority;
        return this;
    }

    /** Its share of the WHOLE machine, 1-100. */
    public JobOptions cpu(int percent) {
        if (percent < 1 || percent > 100) throw Check.usage("cpu is a share of the whole machine, 1 to 100");
        this.cpu = percent;
        return this;
    }

    /** Its memory, in megabytes. */
    public JobOptions memMb(long mb) {
        if (mb < 1) throw Check.usage("mem is a positive number of megabytes");
        this.memMb = mb;
        return this;
    }

    /** Its memory: {@code 512M}, {@code 2G}, or megabytes. */
    public JobOptions mem(String size) {
        this.memMb = Check.memMb(size);
        return this;
    }

    /** Keep the desk awake while it runs ({@code false}: let it sleep); unset, the desk's default. */
    public JobOptions keepAwake(boolean keepAwake) {
        this.keepAwake = keepAwake;
        return this;
    }

    /** The directory it starts in on the desk. */
    public JobOptions cwd(String cwd) {
        this.cwd = Check.cwd(cwd);
        return this;
    }

    /** The shell that runs the command line (not {@link Shell#DEFAULT} or {@link Shell#NONE}; unset, {@code sh -c} or {@code cmd /c}). */
    public JobOptions shell(Shell shell) {
        if (!shell.forJobs()) throw Check.usage("a job's shell is one of sh, bash, zsh, cmd, pwsh, powershell");
        this.shell = shell;
        return this;
    }

    /** Environment variables for it (never logged by the desk). Replaces any set before. */
    public JobOptions env(Map<String, String> env) {
        this.env = Check.env(env);
        return this;
    }

    /** One environment variable. */
    public JobOptions env(String name, String value) {
        Map<String, String> m = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        m.put(name, value);
        this.env = Check.env(m);
        return this;
    }
}
