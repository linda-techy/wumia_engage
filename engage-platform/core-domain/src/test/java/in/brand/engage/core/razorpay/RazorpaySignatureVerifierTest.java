package in.brand.engage.core.razorpay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RazorpaySignatureVerifierTest {

    // Expected signature computed independently (Python hmac/hashlib).
    static final String SECRET = "rzp_webhook_test_secret";
    static final byte[] BODY = """
            {"entity":"event","event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_T1","amount":129900}}}}"""
            .getBytes(StandardCharsets.UTF_8);
    static final String SIG = "4cbf4ca1f21b8ec0b73ed2376641efabf661dda09991c20d808930c1a6ee0717";

    final RazorpaySignatureVerifier verifier = new RazorpaySignatureVerifier(SECRET);

    @Test void accepts_valid_signature() {
        assertTrue(verifier.verify(BODY, SIG));
    }

    @Test void rejects_tampered_body() {
        var tampered = new String(BODY, StandardCharsets.UTF_8).replace("129900", "100")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.verify(tampered, SIG));
    }

    @Test void rejects_reformatted_body() {
        // Same JSON, different whitespace: why the RAW bytes must be verified.
        var pretty = new String(BODY, StandardCharsets.UTF_8).replace(",", ", ").getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.verify(pretty, SIG));
    }

    @Test void rejects_signature_made_with_wrong_secret() {
        assertFalse(new RazorpaySignatureVerifier("the_api_key_secret_by_mistake").verify(BODY, SIG));
    }

    @Test void rejects_missing_header() {
        assertFalse(verifier.verify(BODY, null));
        assertFalse(verifier.verify(BODY, " "));
    }

    @Test void refuses_blank_secret_at_construction() {
        assertThrows(IllegalArgumentException.class, () -> new RazorpaySignatureVerifier(" "));
    }
}
