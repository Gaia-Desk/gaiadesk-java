package net.gaiadesk.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One HTTP request, as an engine sends it. */
public final class HttpCall {
    public final String method;
    public final URI uri;
    public final Map<String, String> headers;
    public final byte @Nullable [] body;
    /** How long to wait for the response's headers (null: as long as it takes). */
    public final @Nullable Duration timeout;

    public HttpCall(String method, URI uri, Map<String, String> headers, byte @Nullable [] body, @Nullable Duration timeout) {
        this.method = method;
        this.uri = uri;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body;
        this.timeout = timeout;
    }
}
