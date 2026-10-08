package net.gaiadesk.internal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import org.jspecify.annotations.Nullable;

/**
 * Connections to the desk's local API: a Unix domain socket (Java 16+, reached by reflection so the SDK
 * stays Java 11 bytecode) or a Windows named pipe (opened as a file, any Java).
 */
public final class LocalConnectors {
    private static final @Nullable Method ADDRESS_OF;
    private static final @Nullable Method OPEN_CHANNEL;
    private static final @Nullable ProtocolFamily UNIX;

    static {
        Method of = null;
        Method open = null;
        ProtocolFamily unix = null;
        try {
            of = Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String.class);
            open = SocketChannel.class.getMethod("open", ProtocolFamily.class);
            unix = StandardProtocolFamily.valueOf("UNIX");
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            of = null;
        }
        ADDRESS_OF = of;
        OPEN_CHANNEL = open;
        UNIX = unix;
    }

    private LocalConnectors() {}

    /** Does this Java have Unix domain sockets (16+)? */
    public static boolean unixSockets() {
        return ADDRESS_OF != null && OPEN_CHANNEL != null && UNIX != null;
    }

    /** A connection to a Unix socket; an IOException when there is none there or it refuses. */
    public static SocketHttpEngine.Connection unix(String path) throws IOException {
        if (ADDRESS_OF == null || OPEN_CHANNEL == null || UNIX == null) throw new IOException("Unix domain sockets need Java 16 or later");
        SocketChannel ch;
        try {
            SocketAddress addr = (SocketAddress) ADDRESS_OF.invoke(null, path);
            ch = (SocketChannel) OPEN_CHANNEL.invoke(null, UNIX);
            try {
                ch.connect(addr);
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(String.valueOf(c), c);
        } catch (IllegalAccessException e) {
            throw new IOException(e.toString(), e);
        }
        // The channel's own read and write (not Channels.newInputStream/newOutputStream, whose read holds a lock a
        // write waits on): reading and writing never block each other.
        InputStream in = channelIn(ch);
        OutputStream out = channelOut(ch);
        return new SocketHttpEngine.Connection() {
            @Override
            public InputStream in() {
                return in;
            }

            @Override
            public OutputStream out() {
                return out;
            }

            @Override
            public void close() {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        };
    }

    /** Reads straight from a channel. */
    public static InputStream channelIn(ByteChannel ch) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int n = ch.read(ByteBuffer.wrap(b, off, len));
                return n < 0 ? -1 : n;
            }
        };
    }

    /** Writes straight to a channel. */
    public static OutputStream channelOut(ByteChannel ch) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer buf = ByteBuffer.wrap(b, off, len);
                while (buf.hasRemaining()) ch.write(buf);
            }
        };
    }

    /** A connection to a Windows named pipe ({@code \\.\pipe\…}); waits briefly while every instance is busy. */
    public static SocketHttpEngine.Connection pipe(String name) throws IOException {
        RandomAccessFile f = null;
        for (int i = 0; ; i++) {
            try {
                f = new RandomAccessFile(name, "rw");
                break;
            } catch (FileNotFoundException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                if (!m.contains("busy") || i >= 40) throw e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted");
                }
            }
        }
        RandomAccessFile file = f;
        InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                return file.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return file.read(b, off, len);
            }
        };
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                file.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                file.write(b, off, len);
            }
        };
        return new SocketHttpEngine.Connection() {
            @Override
            public InputStream in() {
                return in;
            }

            @Override
            public OutputStream out() {
                return out;
            }

            @Override
            public void close() {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        };
    }
}
