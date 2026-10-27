package in.brand.engage.channels.whatsapp;

import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.WaTemplateCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Graph API {@code /messages} request bodies. */
public final class WaPayloads {

    private WaPayloads() {}

    /** A template message, or a free-form text reply when the message carries no template call. */
    static Map<String, Object> message(String to, RenderedMessage m) {
        var out = new LinkedHashMap<String, Object>();
        out.put("messaging_product", "whatsapp");
        out.put("recipient_type", "individual");
        out.put("to", to);
        var wa = m.whatsapp();
        var buttons = replyButtons(m);
        if (wa == null && !buttons.isEmpty()) {
            out.put("type", "interactive");
            out.put("interactive", Map.of("type", "button", "body", Map.of("text", m.body()),
                    "action", Map.of("buttons", buttons)));
        } else if (wa == null) {
            out.put("type", "text");
            out.put("text", Map.of("preview_url", m.body().contains("https://"), "body", m.body()));
        } else {
            out.put("type", "template");
            out.put("template", template(wa));
        }
        return out;
    }

    /** {@code extra.reply_buttons}: one "id<TAB>label" per line, from a free-form template's quick replies. */
    public static List<Map<String, Object>> replyButtons(RenderedMessage m) {
        var raw = m.extra() == null ? null : m.extra().get(REPLY_BUTTONS);
        if (raw == null || raw.isBlank()) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (var line : raw.split("\n")) {
            var parts = line.split("\t", 2);
            if (parts.length == 2) out.add(Map.of("type", "reply", "reply", Map.of("id", parts[0], "title", parts[1])));
        }
        return out;
    }

    /** The {@link RenderedMessage#extra()} key the router fills for a free-form reply with buttons. */
    public static final String REPLY_BUTTONS = "reply_buttons";

    static Map<String, Object> template(WaTemplateCall wa) {
        var components = new ArrayList<Map<String, Object>>();
        if (wa.headerImage() != null) {
            components.add(Map.of("type", "header", "parameters",
                    List.of(Map.of("type", "image", "image", Map.of("link", wa.headerImage())))));
        }
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
        if (wa.couponCode() != null) {
            components.add(Map.of("type", "button", "sub_type", "copy_code", "index", Integer.toString(wa.couponIndex()),
                    "parameters", List.of(Map.of("type", "coupon_code", "coupon_code", wa.couponCode()))));
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
