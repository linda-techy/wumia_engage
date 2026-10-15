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
