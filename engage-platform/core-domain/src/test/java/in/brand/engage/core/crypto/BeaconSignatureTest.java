package in.brand.engage.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BeaconSignatureTest {

    final BeaconSignature beacons = BeaconSignature.fromAppSecret("shpss_test_secret");

    @Test void a_signature_verifies_for_its_own_send_only() {
        var sig = beacons.sign(42);
        assertTrue(beacons.verify(42, sig));
        assertFalse(beacons.verify(43, sig), "a signature cannot be replayed onto another send");
    }

    @Test void another_secret_does_not_verify() {
        var other = BeaconSignature.fromAppSecret("shpss_other_secret");
        assertFalse(other.verify(42, beacons.sign(42)));
    }

    @Test void missing_or_tampered_signatures_fail() {
        var sig = beacons.sign(42);
        assertFalse(beacons.verify(42, null));
        assertFalse(beacons.verify(42, ""));
        assertFalse(beacons.verify(42, sig.substring(1)));
    }

    @Test void the_signature_is_short_and_url_safe() {
        var sig = beacons.sign(9_000_000_001L);
        assertEquals(22, sig.length());
        assertTrue(sig.matches("[A-Za-z0-9_-]+"), sig);
    }

    @Test void an_unset_secret_fails_loudly() {
        assertThrows(IllegalStateException.class, () -> BeaconSignature.fromAppSecret(""));
        assertThrows(IllegalStateException.class, () -> BeaconSignature.fromAppSecret("CHANGE_ME"));
    }
}
