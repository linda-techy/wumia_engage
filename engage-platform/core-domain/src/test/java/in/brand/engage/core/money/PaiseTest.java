package in.brand.engage.core.money;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaiseTest {

    @Test void shopify_rupee_strings_become_paise() {
        assertEquals(129900, Paise.ofRupees("1299.00").value());
        assertEquals(129950, Paise.ofRupees("1299.5").value());
        assertEquals(100, Paise.ofRupees("1").value());
    }

    @Test void refuses_to_round_money() {
        assertThrows(ArithmeticException.class, () -> Paise.ofRupees("12.345"));
    }

    @Test void refuses_negative_amounts() {
        assertThrows(IllegalArgumentException.class, () -> Paise.of(-1));
    }

    @Test void indian_digit_grouping() {
        // java.text.NumberFormat gets these wrong even with en-IN ("172,000").
        assertEquals("0", Paise.of(0).toRupeeString());
        assertEquals("99", Paise.of(9_900).toRupeeString());
        assertEquals("999", Paise.of(99_900).toRupeeString());
        assertEquals("1,000", Paise.of(100_000).toRupeeString());
        assertEquals("1,299", Paise.of(129_900).toRupeeString());
        assertEquals("10,000", Paise.of(1_000_000).toRupeeString());
        assertEquals("1,00,000", Paise.of(10_000_000).toRupeeString());
        assertEquals("1,72,000", Paise.of(17_200_000).toRupeeString());
        assertEquals("10,00,000", Paise.of(100_000_000).toRupeeString());
        assertEquals("1,00,00,000", Paise.of(1_000_000_000).toRupeeString());
        assertEquals("12,34,56,789.50", Paise.of(12_345_678_950L).toRupeeString());
    }

    @Test void paise_are_zero_padded() {
        assertEquals("1,299.50", Paise.of(129_950).toRupeeString());
        assertEquals("1,299.05", Paise.of(129_905).toRupeeString());
    }

    @Test void match_tolerance_is_in_paise() {
        assertTrue(Paise.of(129_900).withinOf(Paise.of(129_950), 100));   // within ₹1
        assertFalse(Paise.of(129_900).withinOf(Paise.of(130_100), 100));
    }
}
