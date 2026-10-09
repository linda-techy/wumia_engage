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
}
