package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What goes to Shopify's discountCodeBasicCreate (Admin API 2026-07). */
class ShopifyDiscountCodesTest {

    @Test
    @SuppressWarnings("unchecked")
    void a_single_use_percentage_code_for_the_whole_order() {
        var start = Instant.parse("2026-10-11T06:30:00Z");
        var d = ShopifyDiscountCodes.input(new DiscountCodes.Spec("WUMAB23CD45", "Abandoned checkout WUMAB23CD45 (run 7)",
                15, 99_900, start, start.plusSeconds(48 * 3600)));

        assertEquals("WUMAB23CD45", d.get("code"));
        assertEquals("2026-10-11T06:30:00Z", d.get("startsAt"));
        assertEquals("2026-10-13T06:30:00Z", d.get("endsAt"));
        assertEquals(Map.of("all", "ALL"), d.get("context"));
        var gets = (Map<String, Object>) d.get("customerGets");
        assertEquals(new BigDecimal("0.15"), ((Map<String, Object>) gets.get("value")).get("percentage"));
        assertEquals(Map.of("all", true), gets.get("items"));
        assertEquals(1, d.get("usageLimit"));
        assertEquals(true, d.get("appliesOncePerCustomer"));
        assertEquals(Map.of("subtotal", Map.of("greaterThanOrEqualToSubtotal", "999.00")), d.get("minimumRequirement"));
    }

    @Test void no_minimum_when_zero() {
        var start = Instant.parse("2026-10-11T06:30:00Z");
        var d = ShopifyDiscountCodes.input(new DiscountCodes.Spec("WUMAB23CD45", "t", 10, 0, start, start.plusSeconds(3600)));
        assertFalse(d.containsKey("minimumRequirement"));
    }

    @Test void codes_avoid_look_alike_characters() {
        for (int i = 0; i < 200; i++) {
            var code = JourneyDiscount.newCode();
            assertTrue(code.matches("WUM[A-HJ-NP-Z2-9]{8}"), code);
            assertFalse(code.substring(3).matches(".*[01OIL].*"), code);
        }
    }
}
