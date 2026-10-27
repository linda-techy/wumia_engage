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
    void a_free_form_reply_with_buttons_is_an_interactive_message() {
        var m = new in.brand.engage.channels.RenderedMessage(in.brand.engage.core.messaging.Channel.WHATSAPP, "inbox_greeting", 7L,
                "inbox_greeting", "", "Hi Priya, welcome to WUMIKA!", "", null, null, java.time.Duration.ofHours(1), false, null,
                null, null, null, Map.of(WaPayloads.REPLY_BUTTONS, "inbox_track\tTrack my order\ninbox_talk\tTalk to us"));
        var p = WaPayloads.message("919876543210", m);
        assertEquals("interactive", p.get("type"));
        var i = (Map<String, Object>) p.get("interactive");
        assertEquals("button", i.get("type"));
        assertEquals(Map.of("text", "Hi Priya, welcome to WUMIKA!"), i.get("body"));
        var buttons = (List<Map<String, Object>>) ((Map<String, Object>) i.get("action")).get("buttons");
        assertEquals(Map.of("type", "reply", "reply", Map.of("id", "inbox_track", "title", "Track my order")), buttons.getFirst());
        assertEquals(2, buttons.size());

        var plain = WaPayloads.message("919876543210", new in.brand.engage.channels.RenderedMessage(
                in.brand.engage.core.messaging.Channel.WHATSAPP, "inbox_auto_reply", 8L, "inbox_auto_reply", "",
                "Track it here: https://t.example/1", "", null, null, java.time.Duration.ofHours(1), false, null, null));
        assertEquals("text", plain.get("type"));
        assertEquals(true, ((Map<String, Object>) plain.get("text")).get("preview_url"), "a link gets its preview");
    }

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

    @Test
    @SuppressWarnings("unchecked")
    void a_copy_code_button_sends_its_coupon_at_its_index() {
        var t = WaPayloads.template(new WaTemplateCall("campaign_sale_live_v1", "en", List.of("Dhanteras Sale"),
                List.of("discount/D15?redirect=%2F"), List.of(), "https://engage.wumika.com/media/a.jpg", "D15", 1));
        var components = (List<Map<String, Object>>) t.get("components");
        assertEquals(Map.of("type", "button", "sub_type", "copy_code", "index", "1",
                "parameters", List.of(Map.of("type", "coupon_code", "coupon_code", "D15"))), components.getLast());
        assertEquals("0", components.get(2).get("index"), "the URL button stays at index 0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_header_photo_goes_first_as_an_image_link() {
        var t = WaPayloads.template(new WaTemplateCall("cart_reminder_v2", "en", List.of("Priya", "Jhumkas"), List.of(),
                List.of(), "https://engage.wumika.com/media/product/8741079023804?v=ab12cd34"));
        var components = (List<Map<String, Object>>) t.get("components");
        assertEquals(2, components.size());
        assertEquals(Map.of("type", "header", "parameters", List.of(Map.of("type", "image",
                "image", Map.of("link", "https://engage.wumika.com/media/product/8741079023804?v=ab12cd34")))), components.get(0));
        assertEquals("body", components.get(1).get("type"));
    }
}
