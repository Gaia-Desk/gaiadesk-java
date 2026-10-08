package examples;

import net.gaiadesk.Chunk;
import net.gaiadesk.ExecOptions;
import net.gaiadesk.ExecStream;
import net.gaiadesk.GaiaDesk;
import net.gaiadesk.RefusedException;
import net.gaiadesk.Shell;
import net.gaiadesk.UnreachableException;
import net.gaiadesk.model.ExecResult;

/**
 * Run a command on a desk, then stream another one's output.
 *
 * <pre>GAIADESK_API_KEY=ak_… GAIADESK_DESK_TOKEN=gdagt_… java examples.ExecOnADesk 123456789</pre>
 */
public final class ExecOnADesk {
    private ExecOnADesk() {}

    public static void main(String[] args) {
        String desk = args.length > 0 ? args[0] : "123456789";
        GaiaDesk gd = GaiaDesk.builder()
                .apiKey(System.getenv("GAIADESK_API_KEY"))
                .deskToken(System.getenv("GAIADESK_DESK_TOKEN"))
                .build();
        try {
            ExecResult r = gd.exec(desk, "uname -a", new ExecOptions().shell(Shell.SH).timeoutSeconds(60));
            System.out.printf("exit %d: %s", r.getExit(), r.getStdout());

            try (ExecStream s = gd.execStream(desk, "for i in 1 2 3; do echo $i; sleep 1; done")) {
                for (Chunk c : s) (c.getStream() == Chunk.Stream.STDOUT ? System.out : System.err).print(c.getText());
                System.out.println("exit " + s.exit().getExitCode());
            }
        } catch (RefusedException e) {
            System.err.println("refused (" + e.getReason() + "): " + e.getMessage());
        } catch (UnreachableException e) {
            System.err.println("unreachable (" + e.getKind() + "): " + e.getMessage());
        }
    }
}
