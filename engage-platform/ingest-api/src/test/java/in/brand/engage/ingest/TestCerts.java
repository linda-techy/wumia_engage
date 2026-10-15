package in.brand.engage.ingest;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** A throwaway self-signed RSA certificate, made with the JDK's keytool (the JDK has no public API for it). */
final class TestCerts {

    record Generated(KeyPair keys, X509Certificate cert) {}

    private TestCerts() {}

    static Generated generate(String dn) throws Exception {
        Path dir = Files.createTempDirectory("sns-test");
        Path store = dir.resolve("k.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-dname", dn, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("keytool failed: " + out);
        var ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(store.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        var cert = (X509Certificate) ks.getCertificate("t");
        var key = (PrivateKey) ks.getKey("t", "changeit".toCharArray());
        return new Generated(new KeyPair(cert.getPublicKey(), key), cert);
    }
}
