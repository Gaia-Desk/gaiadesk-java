package net.gaiadesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A raw TCP "HTTP server" with no framework in between, for the ways a real server or proxy fails: accept a
 * request and close the socket before any response byte (FIN or RST, with or without reading the body), answer
 * the headers and part of the body and then go silent with the socket open, or never answer at all. It proves
 * what the SDK does on the wire itself, not what a test harness happens to do.
 */
final class RawServer implements AutoCloseable {
    enum Mode {
        /** Read the request's headers, then close (FIN) before any response byte, leaving the body unread. */
        CLOSE_BEFORE_RESPONSE,
        /** Read the headers, then reset the connection (RST) before any response byte. */
        RESET_BEFORE_RESPONSE,
        /** Read the whole request (headers and its Content-Length or chunked body), then close before any response byte. */
        CLOSE_AFTER_BODY,
        /** Send 200 headers and one chunk of a chunked body, then nothing, with the socket left open. */
        STALL_MID_BODY,
        /** Send 200 headers with a Content-Length larger than what follows, then nothing, the socket open. */
        STALL_MID_JSON,
        /** Send 200 text/event-stream headers and one stdout event, then nothing, the socket open. */
        STALL_MID_EVENTS,
        /** Read the request and never answer. */
        SILENT,
    }

    private final ServerSocket listener;
    private final Thread acceptor;
    private final List<Socket> held = new ArrayList<>();
    private final Map<String, Integer> byMethod = new HashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean stopped;
    volatile Mode mode;
    final String url;

    RawServer(Mode mode) throws IOException {
        this.mode = mode;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 512);
        url = "http://127.0.0.1:" + listener.getLocalPort() + "/v1";
        acceptor = new Thread(this::acceptLoop, "raw-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Requests received with this method. */
    int count(String method) {
        synchronized (byMethod) {
            return byMethod.getOrDefault(method, 0);
        }
    }

    int connections() {
        return connections.get();
    }

    private void acceptLoop() {
        while (!stopped) {
            Socket c;
            try {
                c = listener.accept();
            } catch (IOException e) {
                return;
            }
            connections.incrementAndGet();
            Thread t = new Thread(() -> serve(c), "raw-server-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** The request head (without its blank line) and whatever of the body came with it; null at end of stream. */
    private static Object[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] one = new byte[4096];
        for (;;) {
            int n = in.read(one);
            if (n < 0) return null;
            buf.write(one, 0, n);
            byte[] all = buf.toByteArray();
            String s = new String(all, StandardCharsets.ISO_8859_1);
            int at = s.indexOf("\r\n\r\n");
            if (at >= 0) {
                byte[] rest = new byte[all.length - at - 4];
                System.arraycopy(all, at + 4, rest, 0, rest.length);
                return new Object[] {s.substring(0, at), rest};
            }
        }
    }

    private void serve(Socket c) {
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            Object[] got = readHead(in);
            if (got == null) {
                c.close();
                return;
            }
            String head = (String) got[0];
            byte[] rest = (byte[]) got[1];
            String method = head.split(" ", 2)[0];
            synchronized (byMethod) {
                byMethod.merge(method, 1, Integer::sum);
            }
            switch (mode) {
                case CLOSE_BEFORE_RESPONSE:
                    c.close();
                    return;
                case RESET_BEFORE_RESPONSE:
                    c.setSoLinger(true, 0);
                    c.close();
                    return;
                case CLOSE_AFTER_BODY:
                    readBody(head, rest, in);
                    c.close();
                    return;
                case STALL_MID_BODY:
                    write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n");
                    break;
                case STALL_MID_JSON:
                    write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"desk\":");
                    break;
                case STALL_MID_EVENTS: {
                    write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");
                    String ev = "event: stdout\ndata: {\"event\":\"stdout\",\"data\":\"hi\"}\n\n";
                    write(out, Integer.toHexString(ev.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + ev + "\r\n");
                    break;
                }
                case SILENT:
                    break;
            }
            synchronized (held) {
                if (stopped) c.close();
                else held.add(c); // held open, silent, until the server stops
            }
        } catch (IOException e) {
            try {
                c.close();
            } catch (IOException ignored) {
                // gone
            }
        }
    }

    /** Reads a Content-Length or chunked body to its end (or the connection's). */
    private static void readBody(String head, byte[] rest, InputStream in) throws IOException {
        long len = 0;
        boolean chunked = false;
        for (String line : head.split("\r\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("content-length:")) len = Long.parseLong(line.substring(15).trim());
            if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
        }
        byte[] b = new byte[65536];
        if (chunked) {
            // Read until the last chunk's "0\r\n\r\n" (or the end of the connection).
            String tail = new String(rest, StandardCharsets.ISO_8859_1);
            while (!tail.endsWith("0\r\n\r\n")) {
                int n = in.read(b);
                if (n < 0) return;
                tail = (tail + new String(b, 0, n, StandardCharsets.ISO_8859_1));
                if (tail.length() > 16) tail = tail.substring(tail.length() - 16);
            }
            return;
        }
        long got = rest.length;
        while (got < len) {
            int n = in.read(b);
            if (n < 0) break;
            got += n;
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        stopped = true;
        try {
            listener.close();
        } catch (IOException ignored) {
            // closed
        }
        synchronized (held) {
            for (Socket s : held) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
            held.clear();
        }
        try {
            acceptor.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
