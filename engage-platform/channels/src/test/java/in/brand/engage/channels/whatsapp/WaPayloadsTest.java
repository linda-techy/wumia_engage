package in.brand.engage.channels.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.WaTemplateCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Template components as the Graph API takes them. */
class WaPayloadsTest {

    @Test
    @SuppressWarnings("unchecked")
    void quick_replies_carry_their_payload_by_button_index() {
        var t = WaPayloads.template(new WaTemplateCall("review_ask_v1", "en", List.of("Priya", "Jhumkas"), List.of(),
                List.of("review_love", "review_help")));
        var components = (List<Map<String, Object>>) t.get("components");
        assertEquals(3, components.size());
        assertEquals(Map.of("type", "button", "sub_type", "quick_reply", "index", "0",
                "parameters", List.of(Map.of("type", "payload", "payload", "review_love"))), components.get(1));
        assertEquals("1", components.get(2).get("index"));
    }
}
