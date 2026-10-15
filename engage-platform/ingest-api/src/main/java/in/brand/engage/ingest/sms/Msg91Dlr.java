package in.brand.engage.ingest.sms;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MSG91 delivery reports, in either of the shapes MSG91 sends (P4-T06),
 * normalised to {@code {"reports":[{request_id, number, status, desc, failed_reason, date}]}}
 * for the inbox:
 *
 * <ul>
 * <li>Push DLR (Flow / v5): form field {@code data} (or a JSON body) holding
 *     {@code [{"requestId", "report":[{"number","status","desc","failedReason","date"}]}]}.</li>
 * <li>Webhook (New): one flat object per message, {@code {"requestId","telNum","status",
 *     "event","upperCaseEventName","failureReason"}}, or an array of them.</li>
 * </ul>
 */
public final class Msg91Dlr {

    private Msg91Dlr() {}

    /** @return the normalised document, or null if the body holds no report */
    public static Map<String, Object> normalise(JsonMapper json, byte[] body, String contentType) throws IOException {
        var text = new String(body, StandardCharsets.UTF_8).strip();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("form-urlencoded")) {
            text = formField(text, "data");
            if (text == null) return null;
        }
        if (text.isEmpty()) return null;
        Object doc = json.readValue(text, Argument.OBJECT_ARGUMENT);
        // {"data": "[...]"} or {"data": [...]}: the push DLR posted as JSON.
        if (doc instanceof Map<?, ?> m && m.containsKey("data") && !m.containsKey("requestId")) {
            doc = m.get("data") instanceof String s ? json.readValue(s, Argument.OBJECT_ARGUMENT) : m.get("data");
        }
        var items = doc instanceof List<?> l ? l : List.of(doc);
        var reports = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            if (!(item instanceof Map<?, ?> m)) continue;
            var requestId = str(m.get("requestId"));
            if (m.get("report") instanceof List<?> rs) {
                for (var r : rs) {
                    if (r instanceof Map<?, ?> rm) {
                        reports.add(report(requestId, str(rm.get("number")), str(rm.get("status")), str(rm.get("desc")),
                                str(rm.get("failedReason")), str(rm.get("date"))));
                    }
                }
            } else if (requestId != null) {
                var desc = first(str(m.get("upperCaseEventName")), str(m.get("event")), str(m.get("desc")));
                reports.add(report(requestId, first(str(m.get("telNum")), str(m.get("number"))), str(m.get("status")), desc,
                        first(str(m.get("failureReason")), str(m.get("failedReason"))), str(m.get("date"))));
            }
        }
        return reports.isEmpty() ? null : Map.of("reports", reports);
    }

    private static Map<String, Object> report(String requestId, String number, String status, String desc,
                                              String failedReason, String date) {
        var r = new LinkedHashMap<String, Object>();
        r.put("request_id", requestId);
        r.put("number", number);
        r.put("status", status);
        r.put("desc", desc == null ? null : desc.toUpperCase(java.util.Locale.ROOT));
        r.put("failed_reason", failedReason);
        r.put("date", date);
        r.values().removeIf(java.util.Objects::isNull);
        return r;
    }

    private static String formField(String body, String name) {
        for (var pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).strip();
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        var s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }

    private static String first(String... values) {
        for (var v : values) if (v != null) return v;
        return null;
    }
}
