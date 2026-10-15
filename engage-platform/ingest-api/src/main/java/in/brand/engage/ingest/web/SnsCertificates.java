package in.brand.engage.ingest.web;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SNS signing certificates, fetched once per URL and cached. Only URLs that
 * {@code SnsSignatureVerifier.trustedCertUrl} accepts ever reach here. Tests
 * replace this bean with their own certificate.
 */
@Singleton
public class SnsCertificates {

    private static final Logger LOG = LoggerFactory.getLogger(SnsCertificates.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<URI, X509Certificate> cache = new ConcurrentHashMap<>();

    public X509Certificate get(URI url) {
        return cache.computeIfAbsent(url, u -> {
            try {
                var res = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).GET().build(), BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) return null;
                return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(res.body()));
            } catch (Exception e) {
                LOG.warn("could not fetch the SNS signing certificate {}: {}", u, e.getMessage());
                return null;
            }
        });
    }
}
