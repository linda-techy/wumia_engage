package in.brand.engage.core.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MetaSignatureVerifierTest {

    // Computed independently: printf '%s' '{"object":"whatsapp_business_account"}' | openssl dgst -sha256 -hmac 'app-secret'
    static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
    static final String HEX = "0bf7374433906636bed1653bc7c64affb42fa5da9a39e251ab95c2e3a9c06923";

    @Test void a_signature_from_the_app_secret_verifies() {
        assertTrue(new MetaSignatureVerifier("app-secret").verify(BODY, "sha256=" + HEX));
        assertTrue(new MetaSignatureVerifier("app-secret").verify(BODY, "sha256=" + HEX.toUpperCase()), "hex case");
    }

    @Test void the_sha256_prefix_is_required() {
        assertFalse(new MetaSignatureVerifier("app-secret").verify(BODY, HEX));
    }

    @Test void a_wrong_secret_or_a_changed_body_fails() {
        assertFalse(new MetaSignatureVerifier("access-token").verify(BODY, "sha256=" + HEX));
        assertFalse(new MetaSignatureVerifier("app-secret").verify("{}".getBytes(StandardCharsets.UTF_8), "sha256=" + HEX));
    }

    @Test void no_secret_verifies_nothing() {
        var v = new MetaSignatureVerifier("");
        assertFalse(v.configured());
        assertFalse(v.verify(BODY, "sha256=" + HEX));
        assertFalse(new MetaSignatureVerifier("app-secret").verify(BODY, null));
    }
}
