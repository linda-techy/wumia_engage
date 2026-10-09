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
        var payload = WhatsAppTemplates.payload(shipped, shipped.locales().get("hi-Latn"));

        assertEquals("order_shipped_v1_hi", payload.get("name"));
        assertEquals("en", payload.get("language"));
        assertEquals("UTILITY", payload.get("category"));
        var components = (List<Map<String, Object>>) payload.get("components");
        var body = components.getFirst();
        assertEquals("Aapka WUMIKA order {{1}} courier {{2}} se ship ho gaya hai (AWB {{3}}). Delivery {{4}} tak expected hai.",
                body.get("text"));
        assertEquals(List.of(List.of("#1042", "Delhivery", "1234567890123", "12 Oct")),
                ((Map<String, Object>) body.get("example")).get("body_text"));
        var button = ((List<Map<String, Object>>) components.get(1).get("buttons")).getFirst();
        assertEquals("https://wumika.com/apps/track/{{1}}", button.get("url"));
        assertEquals(List.of("https://wumika.com/apps/track/1042"), button.get("example"));
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
