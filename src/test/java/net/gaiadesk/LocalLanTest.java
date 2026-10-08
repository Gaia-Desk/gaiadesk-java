package net.gaiadesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolFamily;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.gaiadesk.e2e.Bytes;
import net.gaiadesk.internal.LocalConnectors;
import net.gaiadesk.model.ExecResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local transport (HTTP/1.1 over the desk's Unix socket, here a socket in front of the mock) and the LAN
 * transport (TLS pinned to the gateway certificate's SHA-256, checked before any byte of a request).
 */
class LocalLanTest {
    // ───────────────────────────── local ─────────────────────────────

    /** A Unix socket that forwards each connection to the mock's TCP port (Java 16+, by reflection: the tests compile for 11). */
    static final class UnixFront implements AutoCloseable {
        final ServerSocketChannel server;
        final Thread accept;

        UnixFront(String path, int port) throws Exception {
            ProtocolFamily unix = StandardProtocolFamily.valueOf("UNIX");
            server = (ServerSocketChannel) ServerSocketChannel.class.getMethod("open", ProtocolFamily.class).invoke(null, unix);
            SocketAddress addr = (SocketAddress) Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String.class).invoke(null, path);
            server.bind(addr);
            accept = new Thread(() -> {
                for (;;) {
                    SocketChannel c;
                    try {
                        c = server.accept();
                    } catch (IOException e) {
                        return;
                    }
                    try {
                        Socket tcp = new Socket("127.0.0.1", port);
                        pump(LocalConnectors.channelIn(c), tcp.getOutputStream(), () -> close(tcp, c));
                        pump(tcp.getInputStream(), LocalConnectors.channelOut(c), () -> close(tcp, c));
                    } catch (IOException e) {
                        close(null, c);
                    }
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        static void close(Socket tcp, SocketChannel c) {
            try {
                if (tcp != null) tcp.close();
                c.close();
            } catch (IOException ignored) {
                // closing
            }
        }

        static void pump(InputStream in, OutputStream out, Runnable done) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    for (int n; (n = in.read(buf)) >= 0; ) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // the other side closed
                }
                done.run();
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    static boolean unixOk() {
        return LocalConnectors.unixSockets() && !LocalApi.isWindows();
    }

    @Test
    void localOverTheDesksSocketWithItsAdminTokenOrAnAgentToken(@TempDir Path dir) throws Exception {
        assumeTrue(unixOk(), "Unix domain sockets need Java 16+ on macOS or Linux");
        Path sockDir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "gd");
        try (MockApi api = new MockApi(MockApi.Mode.LOCAL, null); UnixFront front = new UnixFront(sockDir.resolve("api.sock").toString(), api.port())) {
            api.desks.put("123456789", new MockApi.Desk());
            Files.writeString(sockDir.resolve("api-token"), "gdlocal_secret\n");
            GaiaDesk gd = GaiaDesk.localBuilder().env(Map.of("GAIADESK_API_DIR", sockDir.toString())).build();
            assertEquals(TransportKind.LOCAL, gd.getTransport());
            assertTrue(front.accept.isAlive());
            assertEquals("123456789", gd.devices().getDevices().get(0).getDeskId());
            ExecResult r = gd.exec("123456789", "hostname");
            assertEquals("ran: hostname é\n", r.getStdout());
            assertEquals("Bearer gdlocal_secret", api.last().header("authorization"));
            assertEquals("localhost", api.last().header("host"));
            assertTrue(api.requests.stream().noneMatch(q -> q.path.equals("/v1/desks/123456789")), "the local API is never sealed: no key lookup");
            StreamResult s = gd.execStream("123456789", "x").collect();
            assertEquals("ran: x é\n", s.getStdout(), "SSE over HTTP/1.1 chunked on the socket");
            gd.uploadText("hello", "123456789", "notes/a.txt");
            assertEquals("hello", new String(gd.downloadBytes("123456789", "notes/a.txt"), java.nio.charset.StandardCharsets.UTF_8), "Content-Length bodies too");
            GaiaDesk agent = GaiaDesk.localBuilder().socketPath(sockDir.resolve("api.sock").toString()).deskToken("gdagt_bot").build();
            agent.stats("123456789");
            assertEquals("gdagt_bot", api.last().header("x-gaiadesk-desk-token"));
            assertNull(api.last().header("authorization"));
            int before = api.requests.size();
            for (org.junit.jupiter.api.function.Executable hosted : new org.junit.jupiter.api.function.Executable[] {
                    () -> gd.device("123456789"), () -> gd.wake("123456789"), () -> gd.audit(), () -> gd.webhooks(),
                    () -> gd.createSupportSession(null), () -> gd.reach("123456789")}) {
                UsageException e = assertThrows(UsageException.class, hosted);
                assertTrue(e.getMessage().contains("not available over the local transport"), e.getMessage());
            }
            assertEquals(before, api.requests.size(), "refused before anything is sent");
        } finally {
            Files.deleteIfExists(sockDir.resolve("api.sock"));
            Files.deleteIfExists(sockDir.resolve("api-token"));
            Files.deleteIfExists(sockDir);
        }
    }

    @Test
    void localNoSocketOrNoTokenIsLocalApiUnavailable(@TempDir Path dir) {
        assumeTrue(unixOk(), "Unix domain sockets need Java 16+ on macOS or Linux");
        GaiaDesk gd = GaiaDesk.localBuilder().socketPath(dir.resolve("none.sock").toString()).token("gdlocal_x").build();
        UnreachableException e = assertThrows(UnreachableException.class, () -> gd.stats("123456789"));
        assertEquals(Reasons.LOCAL_API_UNAVAILABLE, e.getReason());
        assertTrue(e.getMessage().startsWith(LocalApi.UNAVAILABLE));
        GaiaDesk noToken = GaiaDesk.localBuilder().env(Map.of("GAIADESK_API_DIR", dir.toString())).build();
        assertEquals(Reasons.LOCAL_API_UNAVAILABLE, assertThrows(UnreachableException.class, () -> noToken.stats("123456789")).getReason());
    }

    // ───────────────────────────── LAN ─────────────────────────────

    static final class Tls {
        final SSLContext context;
        final String fingerprint;

        Tls(SSLContext context, String fingerprint) {
            this.context = context;
            this.fingerprint = fingerprint;
        }
    }

    /** A self-signed certificate made by this JDK's keytool, CN=localhost (the tests dial 127.0.0.1: no name is checked). */
    static Tls selfSigned(Path dir) throws Exception {
        File ks = dir.resolve("lan.p12").toFile();
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + (LocalApi.isWindows() ? "keytool.exe" : "keytool");
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "lan", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-validity", "3650", "-keystore", ks.getPath(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        Certificate cert = store.getCertificate("lan");
        String fp = Lan.normalizeFingerprint(Bytes.toHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        return new Tls(ctx, fp);
    }

    @Test
    void lanPinnedTlsAgentTokensOnlyAMismatchSendsNothing(@TempDir Path dir) throws Exception {
        Tls tls = selfSigned(dir);
        try (MockApi api = new MockApi(MockApi.Mode.LAN, tls.context)) {
            api.desks.put("123456789", new MockApi.Desk());
            GaiaDesk gd = GaiaDesk.lanBuilder(api.url, tls.fingerprint.replace(":", "").toUpperCase()).deskToken("gdagt_bot").build();
            assertEquals(TransportKind.LAN, gd.getTransport());
            assertEquals("ran: uptime é\n", gd.exec("123456789", "uptime").getStdout());
            assertEquals("gdagt_bot", api.last().header("x-gaiadesk-desk-token"));
            assertEquals("ran: s é\n", gd.execStream("123456789", "s").collect().getStdout());
            assertTrue(gd.devices().getDevices().size() >= 1);

            String wrong = Lan.normalizeFingerprint("ab".repeat(32));
            GaiaDesk impostor = GaiaDesk.lanBuilder(api.url, wrong).deskToken("gdagt_bot").retry(RetryPolicy.none()).build();
            int before = api.requests.size();
            FingerprintMismatchException e = assertThrows(FingerprintMismatchException.class, () -> impostor.exec("123456789", "uptime"));
            assertEquals(Reasons.FINGERPRINT_MISMATCH, e.getReason());
            assertEquals(wrong, e.getExpected());
            assertEquals(tls.fingerprint, e.getActual());
            assertTrue(e.getMessage().contains("Do not proceed"));
            assertEquals(before, api.requests.size(), "not a byte of the request was sent");

            GaiaDesk noToken = GaiaDesk.lanBuilder(api.url, tls.fingerprint).build();
            int n = api.requests.size();
            assertTrue(assertThrows(UsageException.class, () -> noToken.stats("123456789")).getMessage().contains("agent token"));
            assertEquals(n, api.requests.size());
            assertThrows(UsageException.class, () -> GaiaDesk.lanBuilder(api.url.replace("https", "http"), tls.fingerprint).build());
            assertThrows(UsageException.class, () -> GaiaDesk.lanBuilder(api.url, "nope").build());
            assertThrows(UsageException.class, () -> gd.audit());
        }
    }
}
