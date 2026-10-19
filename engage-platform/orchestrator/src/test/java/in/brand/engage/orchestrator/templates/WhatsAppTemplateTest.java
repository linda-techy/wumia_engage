package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P4-T03: WhatsApp templates as code → what Meta approves and what the adapter sends. */
class WhatsAppTemplateTest {

    static final String SHIPPED = """
            key: order_shipped_v1
            channel: whatsapp
            category: utility
            locales:
              en:      { body: "Your order {{order_no}} has shipped with {{courier}}. Order {{order_no}} again here." }
              hi-Latn: { body: "Aapka order {{order_no}} courier {{courier}} se ship ho gaya hai." }
            buttons:
              - { text: "Track order", url: "https://wumika.com/apps/track/{{track_ref}}" }
            vars: [order_no, courier, track_ref]
            samples: { order_no: "#1042", courier: "Delhivery", track_ref: "1042" }
            """;

    static MessageTemplate parse(String yaml) {
        return TemplateRegistry.parse(yaml, "test.yaml");
    }

    static List<String> lint(String yaml) {
        return TemplateLinter.standard().lint(parse(yaml));
    }

    @Test void hinglish_is_its_own_meta_template_named_key_hi_in_english() {
        var t = parse(SHIPPED);
        assertEquals("order_shipped_v1", t.locales().get("en").providerName());
        assertEquals("order_shipped_v1_hi", t.locales().get("hi-Latn").providerName());
        assertEquals("en", t.locales().get("hi-Latn").language());
        assertEquals(List.of(), TemplateLinter.standard().lint(t));
    }

    @Test void meta_gets_positional_placeholders_numbered_by_first_appearance() {
        assertEquals("Your order {{1}} has shipped with {{2}}. Order {{1}} again here.",
                Renderer.metaBody(parse(SHIPPED).locales().get("en").body()));
        assertEquals("https://wumika.com/apps/track/{{1}}", Renderer.metaUrl("https://wumika.com/apps/track/{{track_ref}}"));
    }

    @Test void rendering_sends_the_values_in_that_order_and_the_button_suffix() {
        var r = new Renderer().whatsapp(parse(SHIPPED), "hi-Latn", Map.of("order_no", "#7", "courier", "BlueDart", "track_ref", "7"));
        assertEquals("order_shipped_v1_hi", r.call().name());
        assertEquals(List.of("#7", "BlueDart"), r.call().bodyParams());
        assertEquals(List.of("7"), r.call().urlButtonSuffixes());
        assertThrows(IllegalArgumentException.class,
                () -> new Renderer().whatsapp(parse(SHIPPED), "en", Map.of("order_no", "#7", "courier", "x")), "missing track_ref");
    }

    @Test void a_service_reply_is_free_form_with_no_meta_call() {
        var t = parse("""
                key: wa_stop_confirm
                channel: whatsapp
                category: service
                locales:
                  en: { body: "You will not get more messages. Reply START to resume." }
                """);
        assertTrue(t.freeForm());
        var r = new Renderer().whatsapp(t, "en", Map.of());
        assertNull(r.call());
        assertEquals("You will not get more messages. Reply START to resume.", r.body());
    }

    @Test void the_lint_refuses_what_meta_would_reject() {
        var starts = lint(SHIPPED.replace("\"Your order {{order_no}}", "\"{{order_no}} is your order"));
        assertTrue(starts.stream().anyMatch(p -> p.contains("starts with a variable")), starts.toString());

        var adjacent = lint(SHIPPED.replace("with {{courier}}.", "with {{courier}} {{order_no}}."));
        assertTrue(adjacent.stream().anyMatch(p -> p.contains("side by side")), adjacent.toString());

        var midUrl = lint(SHIPPED.replace("track/{{track_ref}}", "{{track_ref}}/track"));
        assertTrue(midUrl.stream().anyMatch(p -> p.contains("at the very end of the URL")), midUrl.toString());

        var promo = lint(SHIPPED.replace("has shipped", "has shipped. Flat 20% off on your next order"));
        assertTrue(promo.stream().anyMatch(p -> p.contains("promotional language")), promo.toString());
    }

    static final String PHOTO = """
            key: cart_v2
            channel: whatsapp
            category: marketing
            header_image: "https://engage.wumika.com/media/product/{{photo}}"
            fallback: cart_v1
            locales:
              en: { body: "*Still in your bag*\\n\\nHi {{name}}, {{product}} is still in your bag. Tap below to see it." }
            vars: [name, product, photo]
            samples: { name: "Priya", product: "Jhumkas", photo: "8741079023804-9f1c2a7e" }
            """;

    static final String TEXT = """
            key: cart_v1
            channel: whatsapp
            category: marketing
            locales:
              en: { body: "Hi {{name}}, {{product}} is still in your bag. Tap below to see it." }
            vars: [name, product]
            samples: { name: "Priya", product: "Jhumkas" }
            """;

    @Test void a_photo_template_renders_its_header_link_and_keeps_the_bold_first_line() {
        var t = parse(PHOTO);
        var call = new Renderer().whatsapp(t, "en", Map.of("name", "Priya", "product", "Jhumkas", "photo", "42-ab12cd34")).call();
        assertEquals("https://engage.wumika.com/media/product/42-ab12cd34", call.headerImage());
        assertEquals(List.of("Priya", "Jhumkas"), call.bodyParams(), "the photo is a header parameter, not a body one");
        assertTrue(t.locales().get("en").body().startsWith("*Still in your bag*\n\nHi "), t.locales().get("en").body());
        assertNull(new Renderer().whatsapp(parse(TEXT), "en", Map.of("name", "P", "product", "J")).call().headerImage());
        assertEquals(List.of(), TemplateLinter.standard().lintAll(List.of(t, parse(TEXT))));
    }

    @Test void the_lint_holds_a_photo_template_to_its_fallback() {
        var lint = TemplateLinter.standard();
        var noFallback = lint.lint(parse(PHOTO.replace("fallback: cart_v1\n", "")));
        assertTrue(noFallback.stream().anyMatch(p -> p.contains("needs a fallback")), noFallback.toString());

        var missing = lint.lintAll(List.of(parse(PHOTO)));
        assertTrue(missing.stream().anyMatch(p -> p.contains("is not a template")), missing.toString());

        var utility = lint.lintAll(List.of(parse(PHOTO), parse(TEXT.replace("category: marketing", "category: utility"))));
        assertTrue(utility.stream().anyMatch(p -> p.contains("same channel and category")), utility.toString());

        var needsMore = lint.lintAll(List.of(parse(PHOTO), parse(TEXT.replace("vars: [name, product]", "vars: [name, product, code]")
                .replace("in your bag.", "in your bag with {{code}}.").replace("product: \"Jhumkas\" }", "product: \"Jhumkas\", code: \"X\" }"))));
        assertTrue(needsMore.stream().anyMatch(p -> p.contains("needs 'code'")), needsMore.toString());

        var cooldown = lint.lintAll(List.of(parse(PHOTO), parse(TEXT.replace("category: marketing", "category: marketing\ncooldown: P1D"))));
        assertTrue(cooldown.stream().anyMatch(p -> p.contains("same cooldown")), cooldown.toString());

        var http = lint.lint(parse(PHOTO.replace("\"https://engage", "\"http://engage")));
        assertTrue(http.stream().anyMatch(p -> p.contains("https link")), http.toString());

        assertThrows(IllegalStateException.class, () -> parse("""
                key: push_x_v1
                channel: push
                category: marketing
                header_image: "https://x/{{url}}"
                locales:
                  en: { title: "T", body: "B" }
                vars: [url]
                samples: { url: "https://x" }
                """), "header_image is WhatsApp only");
    }

    @Test void a_photo_template_and_its_fallback_share_one_cooldown() {
        var registry = new TemplateRegistry(null, List.of(parse(PHOTO), parse(TEXT)), TemplateLinter.standard());
        assertEquals(java.util.Set.of("cart_v1", "cart_v2"), registry.cooldownKeys("cart_v2"));
        assertEquals(java.util.Set.of("cart_v1", "cart_v2"), registry.cooldownKeys("cart_v1"));
    }

    @Test void quick_replies_lint_and_render_to_payloads() {
        var ok = """
                key: ask_v1
                channel: whatsapp
                category: marketing
                locales:
                  en: { body: "Hi {{name}}, how are you liking it?" }
                buttons:
                  - { text: "Love it", reply: "review_love" }
                  - { text: "Need help", reply: "review_help" }
                vars: [name]
                samples: { name: "Priya" }
                """;
        assertEquals(List.of(), lint(ok));
        var call = new Renderer().whatsapp(parse(ok), "en", Map.of("name", "Priya")).call();
        assertEquals(List.of("review_love", "review_help"), call.quickReplyPayloads());
        assertEquals(List.of(), call.urlButtonSuffixes());

        var mixed = ok.replace("  - { text: \"Need help\", reply: \"review_help\" }",
                "  - { text: \"Shop\", url: \"https://wumika.com/shop\" }");
        assertTrue(lint(mixed).stream().anyMatch(p -> p.contains("mix of quick-reply and URL")), lint(mixed).toString());
        assertTrue(lint(ok.replace("review_love", "Review Love")).stream().anyMatch(p -> p.contains("lowercase")));
    }
}
