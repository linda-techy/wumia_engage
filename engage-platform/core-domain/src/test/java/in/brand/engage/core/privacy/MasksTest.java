package in.brand.engage.core.privacy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MasksTest {

    @Test void keeps_the_first_character_and_the_domain() {
        assertEquals("m••••@example.com", Masks.email("mary@example.com"));
        assertEquals("a••••@wumika.com", Masks.email("a@wumika.com"));
    }

    @Test void a_value_that_is_not_an_email_is_fully_masked() {
        assertEquals("••••", Masks.email("not-an-email"));
        assertEquals("••••", Masks.email(""));
        assertEquals("••••", Masks.email("@nobody.com"));
        assertNull(Masks.email(null));
    }

    @Test void an_opaque_id_shows_only_its_tail() {
        assertEquals("••••abcdef", Masks.opaque("fcm-token-xyz-abcdef"));
        assertEquals("••••", Masks.opaque("short"));
        assertNull(Masks.opaque(null));
    }
}
