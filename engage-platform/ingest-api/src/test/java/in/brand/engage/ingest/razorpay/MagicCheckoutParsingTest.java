package in.brand.engage.ingest.razorpay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MagicCheckoutParsingTest {

    @Test
    void a_decimal_point_means_rupees() {
        assertEquals(49_900L, MagicCheckoutInboxHandler.toPaise("499.00", false, null));
    }

    @Test
    void an_integer_follows_the_configured_unit() {
        assertEquals(49_900L, MagicCheckoutInboxHandler.toPaise("49900", false, null));
        assertEquals(49_900L, MagicCheckoutInboxHandler.toPaise("499", true, null));
    }

    @Test
    void a_cart_we_hold_settles_the_unit() {
        assertEquals(49_900L, MagicCheckoutInboxHandler.toPaise("499", false, 49_900L), "rupees, despite paise config");
        assertEquals(49_900L, MagicCheckoutInboxHandler.toPaise("49900", true, 49_900L), "paise, despite rupees config");
    }

    @Test
    void junk_is_no_amount() {
        assertNull(MagicCheckoutInboxHandler.toPaise("", false, null));
        assertNull(MagicCheckoutInboxHandler.toPaise("abc", false, null));
        assertNull(MagicCheckoutInboxHandler.toPaise("-5", false, null));
    }

    @Test
    void the_reminder_refills_the_cart_so_magic_opens_again() {
        assertEquals("/cart/1:2,3:1?storefront=true", MagicCheckoutInboxHandler.recoveryUrl("1:2,3:1", "https://x/y"));
        assertEquals("https://x/y", MagicCheckoutInboxHandler.recoveryUrl(null, "https://x/y"));
        assertNull(MagicCheckoutInboxHandler.recoveryUrl(" ", null));
    }

    @Test
    void the_session_token_else_one_from_the_cart() {
        assertEquals("mgc_1", MagicCheckoutInboxHandler.checkoutToken(" mgc_1 ", "cart"));
        assertEquals("magic:cart", MagicCheckoutInboxHandler.checkoutToken("", "cart"));
        assertNull(MagicCheckoutInboxHandler.checkoutToken(null, null));
    }
}
