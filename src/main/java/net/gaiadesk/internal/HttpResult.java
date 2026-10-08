package net.gaiadesk.internal;

import java.io.Closeable;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/** A response whose headers have arrived; its body is read as it comes. {@link #close()} abandons it. */
public interface HttpResult extends Closeable {
    int status();

    /** A header, by any case of its name; null when absent. */
    @Nullable String header(String name);

    InputStream body();

    @Override
    void close();
}
