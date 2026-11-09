package in.brand.engage.ingest.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MagicCheckoutWebhookAuthTest {

    @Test
    void only_the_configured_secret_passes_and_a_short_or_blank_one_never_does() {
        var secret = "0123456789abcdef0123";
        assertTrue(MagicCheckoutWebhookController.authorised(secret, secret));
        assertFalse(MagicCheckoutWebhookController.authorised(secret, secret + "x"));
        assertFalse(MagicCheckoutWebhookController.authorised(secret, null));
        assertFalse(MagicCheckoutWebhookController.authorised("", ""));
        assertFalse(MagicCheckoutWebhookController.authorised("short", "short"));
    }
}
