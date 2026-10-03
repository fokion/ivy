package xyz.fokion.ivy.core.connectors;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS settings of the http connector: PEM roots, client certificates, verification modes. */
final class Tls {

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Tls() {
    }

    /**
     * Builds the SSL context.
     *
     * @param extraRoots PEM certificates trusted in addition to the system ones, or {@code null}
     * @param verifyHost the host name certificates must match when it differs from the URL host
     *                   (with {@code resolve}), or {@code null}
     */
    static SSLContext context(boolean insecure, byte[] extraRoots, byte[] clientCert, byte[] clientKey,
            String verifyHost) throws GeneralSecurityException {
        TrustManager trust;
        if (insecure) {
            trust = new TrustAll();
        } else {
            X509TrustManager base = trustManager(extraRoots);
            trust = new HostChecking(base, verifyHost);
        }
        KeyManager[] keys = null;
        if (clientCert != null && clientKey != null) {
            keys = keyManagers(clientCert, clientKey);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keys, new TrustManager[] {trust}, null);
        return ctx;
    }

    private static X509TrustManager trustManager(byte[] extraRoots) throws GeneralSecurityException {
        TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore) null);
        X509TrustManager systemTm = first(system.getTrustManagers());
        if (extraRoots == null) {
            return systemTm;
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        int i = 0;
        for (X509Certificate c : systemTm.getAcceptedIssuers()) {
            ks.setCertificateEntry("system-" + i++, c);
        }
        Collection<? extends Certificate> extra = certificates(extraRoots);
        if (extra.isEmpty()) {
            throw new CertificateException("WithTLSRootCA: failed to add a certificate to the cert pool");
        }
        for (Certificate c : extra) {
            ks.setCertificateEntry("extra-" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return first(tmf.getTrustManagers());
    }

    private static X509TrustManager first(TrustManager[] tms) {
        for (TrustManager tm : tms) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("no X509 trust manager");
    }

    private static Collection<? extends Certificate> certificates(byte[] pem) throws CertificateException {
        return CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem));
    }

    private static KeyManager[] keyManagers(byte[] certPem, byte[] keyPem) throws GeneralSecurityException {
        List<Certificate> chain = new ArrayList<>(certificates(certPem));
        PrivateKey key = privateKey(new String(keyPem, java.nio.charset.StandardCharsets.US_ASCII));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try {
            ks.load(null, null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        char[] password = new char[0];
        ks.setKeyEntry("client", key, password, chain.toArray(Certificate[]::new));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        return kmf.getKeyManagers();
    }

    /** Reads PKCS#8 ({@code PRIVATE KEY}) and PKCS#1 ({@code RSA PRIVATE KEY}) PEM keys. */
    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        Matcher m = PEM.matcher(pem);
        if (!m.find()) {
            throw new GeneralSecurityException("failed to parse x509 mTLS certificate or key: no PEM key found");
        }
        String type = m.group(1);
        byte[] der = Base64.getMimeDecoder().decode(m.group(2));
        return switch (type) {
            case "PRIVATE KEY" -> {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
                GeneralSecurityException last = null;
                for (String alg : List.of("RSA", "EC", "Ed25519", "Ed448")) {
                    try {
                        yield KeyFactory.getInstance(alg).generatePrivate(spec);
                    } catch (GeneralSecurityException e) {
                        last = e;
                    }
                }
                throw last;
            }
            case "RSA PRIVATE KEY" -> KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
            default -> throw new GeneralSecurityException("unsupported key type \"" + type
                    + "\", convert it to PKCS#8 (openssl pkcs8 -topk8 -nocrypt)");
        };
    }

    /** Wraps a PKCS#1 RSA key in a PKCS#8 structure. */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = der((byte) 0x04, pkcs1);
        byte[] body = new byte[version.length + algorithm.length + octet.length];
        System.arraycopy(version, 0, body, 0, version.length);
        System.arraycopy(algorithm, 0, body, version.length, algorithm.length);
        System.arraycopy(octet, 0, body, version.length + algorithm.length, octet.length);
        return der((byte) 0x30, body);
    }

    private static byte[] der(byte tag, byte[] content) {
        int len = content.length;
        byte[] lenBytes;
        if (len < 0x80) {
            lenBytes = new byte[] {(byte) len};
        } else if (len < 0x100) {
            lenBytes = new byte[] {(byte) 0x81, (byte) len};
        } else if (len < 0x10000) {
            lenBytes = new byte[] {(byte) 0x82, (byte) (len >> 8), (byte) len};
        } else {
            lenBytes = new byte[] {(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        }
        byte[] out = new byte[1 + lenBytes.length + len];
        out[0] = tag;
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(content, 0, out, 1 + lenBytes.length, len);
        return out;
    }

    /** Accepts every certificate and host: {@code ignore_verify_ssl}. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Checks the chain with the delegate and the host name itself, so that a certificate can be
     * matched against the original host when the URL was rewritten by {@code resolve}.
     */
    private static final class HostChecking extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;
        private final String host;

        HostChecking(X509TrustManager delegate, String host) {
            this.delegate = delegate;
            this.host = host;
        }

        private void check(X509Certificate[] chain, String authType, String peerHost) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            String expected = host != null ? host : peerHost;
            if (expected == null) {
                throw new CertificateException("x509: no host name to verify the certificate against");
            }
            if (!matches(chain[0], expected)) {
                throw new CertificateException("x509: certificate is not valid for " + expected);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain, authType, engine == null ? null : engine.getPeerHost());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain, authType, null);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, null);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    /** Matches subject alternative names (DNS with wildcards, IP addresses); like Go, the CN is ignored. */
    static boolean matches(X509Certificate cert, String host) throws CertificateParsingException {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return false;
        }
        for (List<?> san : sans) {
            int type = (Integer) san.get(0);
            String value = String.valueOf(san.get(1)).toLowerCase(Locale.ROOT);
            if (type == 2 && dnsMatches(value, h)) {
                return true;
            }
            if (type == 7 && sameAddress(value, h)) {
                return true;
            }
        }
        return false;
    }

    /** Compares IP addresses in any notation ({@code ::1} and {@code 0:0:0:0:0:0:0:1}). */
    private static boolean sameAddress(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (!b.matches("[0-9a-f:.]+")) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(a).equals(java.net.InetAddress.getByName(b));
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private static boolean dnsMatches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            int dot = host.indexOf('.');
            return dot > 0 && host.substring(dot).equals(pattern.substring(1));
        }
        return pattern.equals(host);
    }

}
