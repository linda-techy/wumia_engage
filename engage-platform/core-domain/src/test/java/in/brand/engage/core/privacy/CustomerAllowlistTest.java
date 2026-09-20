package in.brand.engage.core.privacy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomerAllowlistTest {

    @Test void empty_is_a_configuration_error_not_allow_all() {
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse("  "));
    }

    @Test void star_allows_everyone() {
        var a = CustomerAllowlist.parse(" * ");
        assertTrue(a.allowAll());
        assertTrue(a.allows("anyone@example.com"));
    }

    @Test void star_cannot_be_mixed_with_emails() {
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse("*, a@example.com"));
    }

    @Test void emails_are_matched_case_and_space_insensitively() {
        var a = CustomerAllowlist.parse("Test.One@Example.com, two@example.com");
        assertFalse(a.allowAll());
        assertEquals(Set.of("test.one@example.com", "two@example.com"), a.emails());
        assertTrue(a.allows("  TEST.ONE@example.COM "));
        assertTrue(a.allows("two@example.com"));
    }

    @Test void everyone_else_is_refused() {
        var a = CustomerAllowlist.parse("test.one@example.com");
        assertFalse(a.allows("other@example.com"));
        assertFalse(a.allows("test.one@example.co"));
        assertFalse(a.allows(null));
    }

    @Test void rejects_values_that_are_not_emails() {
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse("not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse("@example.com"));
        assertThrows(IllegalArgumentException.class, () -> CustomerAllowlist.parse("a@b@example.com"));
    }

    @Test void logs_never_print_addresses() {
        assertEquals("1 allowlisted customer(s)", CustomerAllowlist.parse("secret@example.com").toString());
        assertEquals("ALL customers", CustomerAllowlist.parse("*").toString());
    }
}
