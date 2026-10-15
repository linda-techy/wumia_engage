package in.brand.engage.channels.whatsapp;

import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.WaTemplateCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Graph API {@code /messages} request bodies. */
final class WaPayloads {

    private WaPayloads() {}

    /** A template message, or a free-form text reply when the message carries no template call. */
    static Map<String, Object> message(String to, RenderedMessage m) {
        var out = new LinkedHashMap<String, Object>();
        out.put("messaging_product", "whatsapp");
        out.put("recipient_type", "individual");
        out.put("to", to);
        var wa = m.whatsapp();
        if (wa == null) {
            out.put("type", "text");
            out.put("text", Map.of("preview_url", false, "body", m.body()));
        } else {
            out.put("type", "template");
            out.put("template", template(wa));
        }
        return out;
    }

    static Map<String, Object> template(WaTemplateCall wa) {
        var components = new ArrayList<Map<String, Object>>();
        if (!wa.bodyParams().isEmpty()) {
            components.add(Map.of("type", "body", "parameters", text(wa.bodyParams())));
        }
        for (int i = 0; i < wa.urlButtonSuffixes().size(); i++) {
            components.add(Map.of("type", "button", "sub_type", "url", "index", Integer.toString(i),
                    "parameters", text(List.of(wa.urlButtonSuffixes().get(i)))));
        }
        for (int i = 0; i < wa.quickReplyPayloads().size(); i++) {
            components.add(Map.of("type", "button", "sub_type", "quick_reply", "index", Integer.toString(i),
                    "parameters", List.of(Map.of("type", "payload", "payload", wa.quickReplyPayloads().get(i)))));
        }
        var t = new LinkedHashMap<String, Object>();
        t.put("name", wa.name());
        t.put("language", Map.of("code", wa.language()));
        if (!components.isEmpty()) t.put("components", components);
        return t;
    }

    private static List<Map<String, Object>> text(List<String> values) {
        return values.stream().map(v -> Map.<String, Object>of("type", "text", "text", v)).toList();
    }
}
