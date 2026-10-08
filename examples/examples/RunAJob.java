package examples;

import java.util.concurrent.CompletableFuture;
import net.gaiadesk.GaiaDesk;
import net.gaiadesk.JobOptions;
import net.gaiadesk.Priority;
import net.gaiadesk.StreamResult;
import net.gaiadesk.WaitOptions;
import net.gaiadesk.model.JobWaitResult;

/**
 * Start a background job, follow its log, wait for it, and read the desk's stats asynchronously meanwhile.
 *
 * <pre>GAIADESK_API_KEY=ak_… GAIADESK_DESK_TOKEN=gdagt_… java examples.RunAJob 123456789</pre>
 */
public final class RunAJob {
    private RunAJob() {}

    public static void main(String[] args) {
        String desk = args.length > 0 ? args[0] : "123456789";
        GaiaDesk gd = GaiaDesk.builder().apiKey(System.getenv("GAIADESK_API_KEY")).deskToken(System.getenv("GAIADESK_DESK_TOKEN")).build();
        gd.runJob(desk, "nightly-build", "make release", new JobOptions().priority(Priority.LOW).cpu(50).keepAwake(true));
        CompletableFuture<Void> stats = gd.async().stats(desk, null)
                .thenAccept(s -> System.out.printf("%s: cpu %.0f%%, %d jobs running%n", s.getHostname(), s.getCpuPercent(), s.getJobsRunning()));
        StreamResult log = gd.followJobLogs(desk, "nightly-build").collect();
        System.out.print(log.getStdout());
        JobWaitResult done = gd.waitJob(desk, "nightly-build", new WaitOptions().timeout("2h"));
        System.out.println(done.isTimedOut() ? "still running" : "exited " + done.getJob().getExitCode());
        stats.join();
    }
}
