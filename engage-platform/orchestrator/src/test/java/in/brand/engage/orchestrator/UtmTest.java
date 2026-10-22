package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UtmTest {

    @Test void appends_to_a_bare_url() {
        assertEquals("https://w.example/cart?utm_source=engage&utm_medium=push&utm_campaign=cart_recovery&utm_content=42",
                Utm.apply("https://w.example/cart", "push", "cart_recovery", 42));
    }

    @Test void keeps_an_existing_query_and_the_fragment_last() {
        assertEquals("https://w.example/p?variant=7&utm_source=engage&utm_medium=push&utm_campaign=back_in_stock&utm_content=9#size",
                Utm.apply("https://w.example/p?variant=7#size", "push", "back_in_stock", 9));
    }

    @Test void a_discount_link_carries_the_utms_on_the_page_it_redirects_to() {
        var tagged = Utm.apply("https://wumika.com/discount/DHANTERAS15?redirect=%2Fcollections%2Fdiwali-gifts",
                "push", "campaign:x", 7);
        assertEquals("https://wumika.com/discount/DHANTERAS15?redirect="
                + java.net.URLEncoder.encode("/collections/diwali-gifts?utm_source=engage&utm_medium=push&utm_campaign=campaign%3Ax&utm_content=7",
                        java.nio.charset.StandardCharsets.UTF_8), tagged);
    }

    @Test void encodes_the_campaign() {
        assertEquals("https://w.example/?utm_source=engage&utm_medium=push&utm_campaign=campaign%3Aabc+1&utm_content=1",
                Utm.apply("https://w.example/?", "push", "campaign:abc 1", 1));
    }
}
