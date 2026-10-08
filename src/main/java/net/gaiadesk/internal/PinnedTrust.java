package net.gaiadesk.internal;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import net.gaiadesk.Lan;
import net.gaiadesk.e2e.Bytes;
import org.jspecify.annotations.Nullable;

/**
 * Trusts exactly one certificate: the one whose SHA-256 is pinned. A self-signed gateway has no chain and no
 * name to check, so neither is; the pin is checked during the handshake, before any byte of a request.
 * (An X509ExtendedTrustManager: the JDK adds no hostname check of its own around it.)
 */
public final class PinnedTrust extends X509ExtendedTrustManager {
    private final String pinned;
    private volatile @Nullable String mismatch;

    public PinnedTrust(String pinned) {
        this.pinned = pinned;
    }

    public String pinned() {
        return pinned;
    }

    /** The fingerprint of the last certificate refused (empty: none presented), or null. */
    public @Nullable String lastMismatch() {
        return mismatch;
    }

    public SSLContext context() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {this}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available: " + e.getMessage(), e);
        }
    }

    private void check(X509Certificate @Nullable [] chain) throws CertificateException {
        String actual = "";
        if (chain != null && chain.length > 0) {
            try {
                actual = Lan.normalizeFingerprint(Bytes.toHex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())));
            } catch (GeneralSecurityException e) {
                throw new CertificateException("cannot fingerprint the certificate", e);
            }
        }
        if (!actual.equals(pinned)) {
            mismatch = actual;
            throw new CertificateException("the certificate's SHA-256 is " + (actual.isEmpty() ? "(none)" : actual) + ", not the pinned " + pinned);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("a client does not check clients");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        throw new CertificateException("a client does not check clients");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        throw new CertificateException("a client does not check clients");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
