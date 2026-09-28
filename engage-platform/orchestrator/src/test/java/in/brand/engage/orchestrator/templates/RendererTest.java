package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RendererTest {

    final Renderer renderer = new Renderer();
    final MessageTemplate backInStock = TemplateRegistry.loadClasspath().stream()
            .filter(t -> t.key().equals("push_back_in_stock_v1")).findFirst().orElseThrow();
    final Map<String, String> vars = Map.of("size", "M", "product", "Linen Kurta",
            "url", "https://wumika.example/products/linen-kurta", "image", "https://cdn.example/k.jpg");

    @Test void renders_the_requested_locale() {
        var r = renderer.render(backInStock, "hi-Latn", vars);
        assertEquals("hi-Latn", r.locale());
        assertEquals("Size M aa gaya: Linen Kurta", r.title());
        assertEquals("Is restock mein kam pieces hain.", r.body());
        assertEquals("https://wumika.example/products/linen-kurta", r.url());
        assertEquals("https://cdn.example/k.jpg", r.image());
    }

    @Test void falls_back_to_en_for_an_unknown_or_missing_locale() {
        assertEquals("Size M is back: Linen Kurta", renderer.render(backInStock, "ta", vars).title());
        assertEquals("en", renderer.render(backInStock, null, vars).locale());
    }

    @Test void a_missing_variable_is_an_error_not_a_blank() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(backInStock, "en", Map.of("size", "M", "url", "u")));
        assertEquals("template push_back_in_stock_v1 needs variable 'product'", e.getMessage());
    }

    @Test void a_long_product_name_is_cut_to_40_characters_with_an_ellipsis() {
        var r = renderer.render(backInStock, "en", Map.of("size", "M", "product",
                "Hand Block Printed Mulmul Cotton Anarkali Kurta", "url", "u"));
        assertEquals(40, Renderer.length(r.title()), r.title());
        assertTrue(r.title().endsWith("…"), r.title());
    }

    @Test void values_containing_dollar_signs_or_braces_are_inserted_literally() {
        var r = renderer.render(backInStock, "en", Map.of("size", "$1", "product", "{{size}}", "url", "u"));
        assertEquals("Size $1 is back: {{size}}", r.title());
    }
}
