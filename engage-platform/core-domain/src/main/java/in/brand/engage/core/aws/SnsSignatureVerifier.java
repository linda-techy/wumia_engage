package in.brand.engage.core.aws;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Amazon SNS message signatures (P7-T01), before an SES bounce or complaint
 * touches the inbox. Signature version 2 only (SHA256withRSA): version 1
 * (SHA1) is refused. The signing certificate must come over https from an
 * {@code sns.<region>.amazonaws.com} host, so a forged message cannot point
 * at its own certificate.
 *
 * <p>The string to sign is the message's fields in AWS's fixed order
 * ({@code Message, MessageId, Subject?, Timestamp, TopicArn, Type} for a
 * notification; {@code Message, MessageId, SubscribeURL, Timestamp, Token,
 * TopicArn, Type} for subscription messages), each as {@code name\nvalue\n}.
 * Certificates are fetched by the caller's {@code certificates} function,
 * which should cache them.
 */
public final class SnsSignatureVerifier {

    static final Pattern CERT_HOST = Pattern.compile("sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?");
    private static final List<String> NOTIFICATION = List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> SUBSCRIPTION = List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final Function<URI, X509Certificate> certificates;

    public SnsSignatureVerifier(Function<URI, X509Certificate> certificates) {
        this.certificates = certificates;
    }

    /** @param message the SNS JSON envelope's top-level string fields */
    public boolean verify(Map<String, String> message) {
        if (!"2".equals(message.get("SignatureVersion"))) return false;
        var certUrl = message.get("SigningCertURL");
        var signature = message.get("Signature");
        if (certUrl == null || signature == null || !trustedCertUrl(certUrl)) return false;
        try {
            var cert = certificates.apply(URI.create(certUrl));
            if (cert == null) return false;
            cert.checkValidity();
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(cert.getPublicKey());
            verifier.update(stringToSign(message).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean trustedCertUrl(String url) {
        try {
            var u = URI.create(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && CERT_HOST.matcher(u.getHost()).matches()
                    && u.getPath() != null && u.getPath().endsWith(".pem");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String stringToSign(Map<String, String> m) {
        var fields = "Notification".equals(m.get("Type")) ? NOTIFICATION : SUBSCRIPTION;
        var sb = new StringBuilder();
        for (var f : fields) {
            var v = m.get(f);
            if (v == null) continue;                 // Subject is optional
            sb.append(f).append('\n').append(v).append('\n');
        }
        return sb.toString();
    }
}
