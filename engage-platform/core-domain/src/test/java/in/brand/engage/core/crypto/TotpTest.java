package in.brand.engage.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test void matches_rfc6238_vectors() {
        // RFC 6238 Appendix B, SHA-1, 8 digits truncated to the last 6.
        assertEquals("287082", Totp.code(RFC_SECRET, Instant.ofEpochSecond(59L)));
        assertEquals("081804", Totp.code(RFC_SECRET, Instant.ofEpochSecond(1111111109L)));
        assertEquals("005924", Totp.code(RFC_SECRET, Instant.ofEpochSecond(1234567890L)));
    }

    @Test void accepts_the_previous_and_next_step() {
        var now = Instant.ofEpochSecond(1111111109L);
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.minusSeconds(30)), now));
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.plusSeconds(30)), now));
    }

    @Test void refuses_a_code_two_steps_away() {
        var now = Instant.ofEpochSecond(1111111109L);
        assertFalse(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.minusSeconds(90)), now));
    }

    @Test void refuses_malformed_codes() {
        var now = Instant.now();
        assertFalse(Totp.verify(RFC_SECRET, "abc", now));
        assertFalse(Totp.verify(RFC_SECRET, "", now));
        assertFalse(Totp.verify(RFC_SECRET, null, now));
    }

    @Test void generated_secrets_are_20_bytes_and_distinct() {
        var a = Totp.generateSecret();
        var b = Totp.generateSecret();
        assertEquals(20, a.length);
        assertNotEquals(Totp.base32(a), Totp.base32(b));
    }

    @Test void provisioning_uri_is_an_otpauth_url_with_base32_secret() {
        var uri = Totp.provisioningUri("Engage", "mary@example.com", RFC_SECRET);
        assertTrue(uri.startsWith("otpauth://totp/Engage:mary%40example.com?"), uri);
        assertTrue(uri.contains("secret=" + Totp.base32(RFC_SECRET)), uri);
        assertTrue(uri.contains("issuer=Engage"), uri);
    }
}
