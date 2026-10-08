package examples;

import java.nio.file.Path;
import net.gaiadesk.GaiaDesk;
import net.gaiadesk.model.CopyResult;

/**
 * Upload a file to a desk and download it back (single files, at most 256 MB through the API).
 *
 * <pre>GAIADESK_API_KEY=ak_… GAIADESK_DESK_TOKEN=gdagt_… java examples.CopyAFile 123456789 ./report.pdf</pre>
 */
public final class CopyAFile {
    private CopyAFile() {}

    public static void main(String[] args) {
        String desk = args.length > 0 ? args[0] : "123456789";
        Path local = Path.of(args.length > 1 ? args[1] : "report.pdf");
        GaiaDesk gd = GaiaDesk.builder().apiKey(System.getenv("GAIADESK_API_KEY")).deskToken(System.getenv("GAIADESK_DESK_TOKEN")).build();
        CopyResult up = gd.upload(local, desk, "Downloads/");
        System.out.println("uploaded " + up.getBytes() + " bytes to " + up.getDestination());
        CopyResult down = gd.download(desk, up.getDestination(), Path.of("copy-of-" + local.getFileName()));
        System.out.println("downloaded " + down.getBytes() + " bytes to " + down.getDestination());
    }
}
