package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P4-T03: what is submitted to Meta is built from the template files, so approved text = sent text. */
class WhatsAppTemplatesPayloadTest {

    @Test
    @SuppressWarnings("unchecked")
    void a_submission_carries_positional_text_examples_and_the_url_button() {
        var shipped = loadAll().stream().filter(x -> x.key().equals("order_shipped_v1")).findFirst().orElseThrow();
        var payload = WhatsAppTemplates.payload(shipped, shipped.locales().get("en"));

        assertEquals("order_shipped_v1", payload.get("name"));
        assertEquals("en", payload.get("language"));
        assertEquals("UTILITY", payload.get("category"));
        var components = (List<Map<String, Object>>) payload.get("components");
        var body = components.getFirst();
        assertEquals("Your WUMIKA order {{1}} has shipped with {{2}} (AWB {{3}}). Track it with the button below.",
                body.get("text"));
        assertEquals(List.of(List.of("#1042", "Delhivery", "1234567890123")),
                ((Map<String, Object>) body.get("example")).get("body_text"));
        var button = ((List<Map<String, Object>>) components.get(1).get("buttons")).getFirst();
        assertEquals("https://wumika.com/apps/push/t/{{1}}", button.get("url"));
        assertEquals(List.of("https://wumika.com/apps/push/t/2s-AbCdEfGhIjK"), button.get("example"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quick_replies_are_submitted_as_quick_reply_buttons() {
        var ask = loadAll().stream().filter(x -> x.key().equals("review_ask_v1")).findFirst().orElseThrow();
        var payload = WhatsAppTemplates.payload(ask, ask.locales().get("en"));
        assertEquals("MARKETING", payload.get("category"));
        var buttons = (List<Map<String, Object>>) ((List<Map<String, Object>>) payload.get("components")).get(1).get("buttons");
        assertEquals(List.of(Map.of("type", "QUICK_REPLY", "text", "Love it"), Map.of("type", "QUICK_REPLY", "text", "Need help")),
                buttons);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_photo_template_is_submitted_with_an_image_header_and_its_sample_photo() {
        var cart = loadAll().stream().filter(x -> x.key().equals("cart_reminder_v2")).findFirst().orElseThrow();
        var payload = WhatsAppTemplates.payload(cart, cart.locales().get("en"), "4::aGFuZGxl");
        var components = (List<Map<String, Object>>) payload.get("components");
        assertEquals(Map.of("type", "HEADER", "format", "IMAGE", "example", Map.of("header_handle", List.of("4::aGFuZGxl"))),
                components.getFirst());
        assertEquals("*Still in your bag*\n\nHi {{1}}, {{2}} is still in your WUMIKA bag. Tap below to pick up where you left off.",
                components.get(1).get("text"), "the photo is not a body variable");
        assertEquals("BUTTONS", components.get(2).get("type"));
        assertEquals("https://engage.wumika.com/media/product/8741079023804-9f1c2a7e",
                new WhatsAppTemplates.Submission(cart, cart.locales().get("en")).samplePhoto());

        var text = loadAll().stream().filter(x -> x.key().equals("cart_reminder_v1")).findFirst().orElseThrow();
        assertNull(new WhatsAppTemplates.Submission(text, text.locales().get("en")).samplePhoto());
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_campaign_template_is_submitted_with_the_poster_header_and_a_copy_code_button() {
        var live = loadAll().stream().filter(x -> x.key().equals("campaign_sale_live_v1")).findFirst().orElseThrow();
        var payload = WhatsAppTemplates.payload(live, live.locales().get("en"), "4::aGFuZGxl");
        var components = (List<Map<String, Object>>) payload.get("components");
        assertEquals("IMAGE", components.getFirst().get("format"));
        var buttons = (List<Map<String, Object>>) components.get(2).get("buttons");
        assertEquals("URL", buttons.get(0).get("type"));
        assertEquals(Map.of("type", "COPY_CODE", "example", "DHANTERAS15"), buttons.get(1));
        assertEquals("https://engage.wumika.com/media/product/8741079023804",
                new WhatsAppTemplates.Submission(live, live.locales().get("en")).samplePhoto());
    }

    static List<in.brand.engage.orchestrator.templates.MessageTemplate> loadAll() {
        try {
            var m = in.brand.engage.orchestrator.templates.TemplateRegistry.class.getDeclaredMethod("loadClasspath");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            var list = (List<in.brand.engage.orchestrator.templates.MessageTemplate>) m.invoke(null);
            return list;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
