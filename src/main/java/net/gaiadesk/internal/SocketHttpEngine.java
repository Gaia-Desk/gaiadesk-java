package net.gaiadesk.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.gaiadesk.Cancellation;
import org.jspecify.annotations.Nullable;

/**
 * HTTP/1.1 over a connection the engine does not open itself: the desk's Unix socket or Windows named pipe
 * (java.net.http speaks TCP only). One request per connection ({@code Connection: close}); bodies with
 * {@code Content-Length}, chunked, or to the end of the connection.
 */
public final class SocketHttpEngine implements HttpEngine {
    /** A connection: its two directions, and closing it. */
    public interface Connection extends Closeable {
        InputStream in();

        OutputStream out();

        @Override
        void close();
    }

    /** Opens a connection; a GaiaDeskException of its own when there is nothing to connect to. */
    public interface Connector {
        Connection open() throws IOException;
    }

    private final Connector connector;
    private final String host;

    public SocketHttpEngine(Connector connector, String host) {
        this.connector = connector;
        this.host = host;
    }

    @Override
    public HttpResult send(HttpCall call, Cancellation cancel) throws IOException {
        Connection conn = connector.open();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = call.timeout == null ? null : Threads.scheduler().schedule(() -> {
            timedOut.set(true);
            conn.close();
        }, Math.max(1, call.timeout.toMillis()), TimeUnit.MILLISECONDS);
        Cancellation.Registration reg = cancel.onCancel(conn::close);
        try {
            OutputStream out = conn.out();
            out.write(head(call).getBytes(StandardCharsets.UTF_8));
            if (call.body != null) out.write(call.body);
            out.flush();
            BufferedInputStream in = new BufferedInputStream(conn.in());
            Head h;
            do {
                h = readHead(in);
            } while (h.status >= 100 && h.status < 200 && h.status != 101);
            if (timer != null) timer.cancel(false);
            InputStream body = bodyOf(call.method, h, in);
            return new Result(h, new FilterInputStream(body) {
                @Override
                public void close() {
                    reg.close();
                    conn.close();
                }
            }, () -> {
                reg.close();
                conn.close();
            });
        } catch (IOException | RuntimeException e) {
            if (timer != null) timer.cancel(false);
            reg.close();
            conn.close();
            if (timedOut.get()) throw new HttpTimeoutException("request timed out");
            throw e;
        }
    }

    private String head(HttpCall call) {
        String target = call.uri.getRawPath() + (call.uri.getRawQuery() == null ? "" : "?" + call.uri.getRawQuery());
        StringBuilder sb = new StringBuilder();
        sb.append(call.method).append(' ').append(target).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        for (Map.Entry<String, String> e : call.headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if (call.body != null) sb.append("Content-Length: ").append(call.body.length).append("\r\n");
        else if (!call.method.equals("GET") && !call.method.equals("HEAD")) sb.append("Content-Length: 0\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    private static final class Head {
        final int status;
        final Map<String, String> headers;

        Head(int status, Map<String, String> headers) {
            this.status = status;
            this.headers = headers;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (;;) {
            int c = in.read();
            if (c < 0) {
                if (b.size() == 0) throw new IOException("the connection closed before the response");
                break;
            }
            if (c == '\n') break;
            b.write(c);
            if (b.size() > 65536) throw new IOException("a response header line is too long");
        }
        String s = new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static Head readHead(InputStream in) throws IOException {
        String status = readLine(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not an HTTP response: " + status);
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status: " + status, e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (;;) {
            String line = readLine(in);
            if (line.isEmpty()) break;
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(i + 1).trim();
            headers.merge(k, v, (a, b) -> a + ", " + b);
        }
        return new Head(code, headers);
    }

    private static InputStream bodyOf(String method, Head h, InputStream in) throws IOException {
        if (method.equals("HEAD") || h.status == 204 || h.status == 304) return InputStream.nullInputStream();
        String te = h.headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) return new Chunked(in);
        String cl = h.headers.get("content-length");
        if (cl != null) {
            try {
                return new Bounded(in, Long.parseLong(cl.trim()));
            } catch (NumberFormatException e) {
                throw new IOException("a bad Content-Length: " + cl, e);
            }
        }
        return in;
    }

    private static final class Result implements HttpResult {
        private final Head head;
        private final InputStream body;
        private final Runnable close;

        Result(Head head, InputStream body, Runnable close) {
            this.head = head;
            this.body = body;
            this.close = close;
        }

        @Override
        public int status() {
            return head.status;
        }

        @Override
        public @Nullable String header(String name) {
            return head.headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            close.run();
        }
    }

    /** A body of a known length. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long left;

        Bounded(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("the connection closed before the body ended");
            left -= n;
            return n;
        }
    }

    /** A chunked body (RFC 9112 §7.1); trailers are read and dropped. */
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                String line = readLine(in);
                int semi = line.indexOf(';');
                String size = (semi >= 0 ? line.substring(0, semi) : line).trim();
                try {
                    left = Long.parseLong(size, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("a bad chunk size: " + line, e);
                }
                if (left == 0) {
                    while (!readLine(in).isEmpty()) {
                        // trailers
                    }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("the connection closed inside a chunk");
            left -= n;
            if (left == 0) readLine(in);
            return n;
        }
    }
}
