package in.brand.engage.core.aws;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SNS signature version 2, with a locally generated key standing in for AWS's. */
class SnsSignatureVerifierTest {

    static final String CERT_URL = "https://sns.ap-south-1.amazonaws.com/SimpleNotificationService-abc.pem";
    static KeyPair keys;
    static X509Certificate cert;

    @BeforeAll static void keys() throws Exception {
        var g = TestCerts.generate("CN=sns.amazonaws.com");
        keys = g.keys();
        cert = g.cert();
    }

    static Map<String, String> notification() throws Exception {
        var m = new HashMap<String, String>();
        m.put("Type", "Notification");
        m.put("MessageId", "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324");
        m.put("TopicArn", "arn:aws:sns:ap-south-1:123456789012:engage-ses");
        m.put("Message", "{\"notificationType\":\"Bounce\"}");
        m.put("Timestamp", "2026-10-15T06:00:00.000Z");
        m.put("SignatureVersion", "2");
        m.put("SigningCertURL", CERT_URL);
        var s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update(SnsSignatureVerifier.stringToSign(m).getBytes(StandardCharsets.UTF_8));
        m.put("Signature", Base64.getEncoder().encodeToString(s.sign()));
        return m;
    }

    final SnsSignatureVerifier verifier = new SnsSignatureVerifier(u -> u.equals(URI.create(CERT_URL)) ? cert : null);

    @Test void a_valid_message_verifies() throws Exception {
        assertTrue(verifier.verify(notification()));
    }

    @Test void a_tampered_message_does_not() throws Exception {
        var m = notification();
        m.put("Message", "{\"notificationType\":\"Complaint\"}");
        assertFalse(verifier.verify(m));
    }

    @Test void a_certificate_from_anywhere_but_sns_is_refused() throws Exception {
        var m = notification();
        m.put("SigningCertURL", "https://evil.example/sns.ap-south-1.amazonaws.com.pem");
        assertFalse(verifier.verify(m));
        assertFalse(SnsSignatureVerifier.trustedCertUrl("http://sns.ap-south-1.amazonaws.com/x.pem"), "https only");
        assertFalse(SnsSignatureVerifier.trustedCertUrl("https://sns.ap-south-1.amazonaws.com.evil.example/x.pem"));
        assertTrue(SnsSignatureVerifier.trustedCertUrl(CERT_URL));
    }

    @Test void signature_version_1_is_refused() throws Exception {
        var m = notification();
        m.put("SignatureVersion", "1");
        assertFalse(verifier.verify(m));
    }
}
