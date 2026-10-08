package net.gaiadesk.internal;

import java.io.IOException;
import net.gaiadesk.Cancellation;

/**
 * Sends one request and returns once the response's headers arrived. Throws an IOException for a connection
 * that failed ({@link java.net.http.HttpTimeoutException} when {@code timeout} ran out), or a
 * {@link net.gaiadesk.GaiaDeskException} of its own (no local socket; a pinned certificate that did not match).
 * A cancelled call throws whatever closing it gave; the caller checks the cancellation.
 */
public interface HttpEngine {
    HttpResult send(HttpCall call, Cancellation cancel) throws IOException, InterruptedException;
}
