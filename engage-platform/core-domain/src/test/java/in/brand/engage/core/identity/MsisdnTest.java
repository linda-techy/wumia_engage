package in.brand.engage.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MsisdnTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "+919876543210",      // Razorpay contact
        "+91 98765 43210",    // typed with spaces
        "919876543210",
        "09876543210",        // leading trunk zero
        "9876543210",         // Shopify checkout, bare 10 digits
        "0091-98765-43210",   // international prefix
        "(+91) 98765-43210"
    })
    void normalises_real_world_formats(String raw) {
        assertEquals("919876543210", Msisdn.normalise(raw).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "02226543210",   // Mumbai landline
        "5876543210",    // not a mobile series
        "98765",         // too short
        "+14155550123",  // not Indian
        "",
        "abc"
    })
    void rejects_non_mobiles(String raw) {
        assertTrue(Msisdn.normalise(raw).isEmpty());
    }

    @Test void null_is_empty() {
        assertTrue(Msisdn.normalise(null).isEmpty());
    }

    @Test void mask_never_shows_the_middle() {
        assertEquals("+91 98•••••210", Msisdn.mask("919876543210"));
    }
}
