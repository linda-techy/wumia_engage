package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P4-T06: SMS templates as code → what DLT registers and what the adapter sends. */
class SmsTemplateTest {

    static final String SHIPPED = """
            key: sms_order_shipped
            channel: sms
            category: utility
            dlt_template_id: "1107170000000000001"
            msg91_template_id: 64f0c0ffee
            truncate: [courier]
            locales:
              en: { body: "Your WUMIKA order {{order_no}} has shipped with {{courier}}. Track: https://wumika.com/apps/track/{{track_ref}} -WUMIKA" }
            vars: [order_no, courier, track_ref]
            samples: { order_no: "#1042", courier: "Delhivery", track_ref: "1042" }
            """;

    static MessageTemplate parse(String yaml) {
        return TemplateRegistry.parse(yaml, "test.yaml");
    }

    static List<String> lint(String yaml) {
        return TemplateLinter.standard().lint(parse(yaml));
    }

    @Test void the_repositorys_sms_templates_lint_clean_and_wait_for_dlt() {
        var sms = TemplateRegistry.loadClasspath().stream()
                .filter(t -> t.channel() == in.brand.engage.core.messaging.Channel.SMS).toList();
        assertEquals(List.of("sms_ndr", "sms_order_shipped", "sms_out_for_delivery", "sms_payment_link"),
                sms.stream().map(MessageTemplate::key).sorted().toList());
        for (var t : sms) {
            assertEquals(List.of(), TemplateLinter.standard().lint(t), t.key());
            assertTrue(t.sms().pending(), t.key() + ": DLT is not registered yet");
        }
    }

    @Test void a_missing_dlt_template_id_fails_at_load() {
        var e = assertThrows(IllegalStateException.class, () -> parse(SHIPPED.replace("dlt_template_id: \"1107170000000000001\"\n", "")));
        assertTrue(e.getMessage().contains("dlt_template_id"), e.getMessage());
    }

    @Test void a_promotional_sms_template_is_refused() {
        var problems = lint(SHIPPED.replace("category: utility", "category: marketing"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("no promotional SMS")), problems.toString());
    }

    @Test void a_dlt_id_must_be_19_digits_or_pending() {
        assertTrue(lint(SHIPPED.replace("1107170000000000001", "1107xxxx")).stream().anyMatch(p -> p.contains("19-digit")));
        assertEquals(List.of(), lint(SHIPPED.replace("\"1107170000000000001\"", "PENDING")));
    }

    @Test void links_must_be_https_on_a_whitelisted_domain() {
        assertTrue(lint(SHIPPED.replace("https://wumika.com", "https://bit.ly")).stream().anyMatch(p -> p.contains("bit.ly")));
        assertTrue(lint(SHIPPED.replace("https://wumika.com", "http://wumika.com")).stream().anyMatch(p -> p.contains("https")));
    }

    @Test void unicode_a_second_part_a_reused_variable_and_a_second_locale_are_refused() {
        assertTrue(lint(SHIPPED.replace("has shipped", "₹ has shipped")).stream().anyMatch(p -> p.contains("GSM-7")));
        assertTrue(lint(SHIPPED.replace("-WUMIKA", "-WUMIKA " + "x".repeat(80))).stream().anyMatch(p -> p.contains("160")));
        assertTrue(lint(SHIPPED.replace("Track:", "Order {{order_no}}. Track:")).stream().anyMatch(p -> p.contains("used twice")));
        assertTrue(lint(SHIPPED.replace("locales:\n", "locales:\n  hi-Latn: { body: \"Aapka order {{order_no}} {{courier}} {{track_ref}}\" }\n"))
                .stream().anyMatch(p -> p.contains("only the 'en' locale")));
    }

    @Test void a_long_sample_needs_truncate() {
        var yaml = SHIPPED.replace("truncate: [courier]\n", "").replace("courier: \"Delhivery\"", "courier: \"Delhivery Express Logistics Pvt Ltd\"");
        assertTrue(lint(yaml).stream().anyMatch(p -> p.contains("over 30")));
    }

    @Test void sms_fields_belong_to_sms_only() {
        assertThrows(IllegalStateException.class, () -> parse(WhatsAppTemplateTest.SHIPPED + "dlt_template_id: PENDING\n"));
    }

    @Test void rendering_gives_the_registered_text_and_one_value_per_slot() {
        var r = new Renderer().sms(parse(SHIPPED), Map.of("order_no", "#1042", "courier", "Delhivery", "track_ref", "1042"));
        assertEquals("Your WUMIKA order #1042 has shipped with Delhivery. Track: https://wumika.com/apps/track/1042 -WUMIKA", r.body());
        assertEquals("Your WUMIKA order {#var#} has shipped with {#var#}. Track: https://wumika.com/apps/track/{#var#} -WUMIKA",
                r.call().dltBody());
        assertEquals(List.of("order_no", "courier", "track_ref"), r.call().names());
        assertEquals(List.of("#1042", "Delhivery", "1042"), r.call().values());
        assertEquals("1107170000000000001", r.call().dltTemplateId());
        assertNull(r.call().mismatch());
    }

    @Test void only_declared_variables_are_cut_to_30() {
        var t = parse(SHIPPED);
        var r = new Renderer().sms(t, Map.of("order_no", "#1042", "courier", "Delhivery Express Logistics Pvt Ltd", "track_ref", "1042"));
        assertEquals("Delhivery Express Logistics Pv", r.call().values().get(1));
        assertNull(r.call().mismatch());

        // Not truncatable: rendered as it is, and the adapter refuses it before any HTTP call.
        var long_ = new Renderer().sms(t, Map.of("order_no", "#1042", "courier", "x", "track_ref", "1".repeat(31)));
        assertNotNull(long_.call().mismatch());
    }

    @Test void a_variable_holding_a_link_or_unicode_does_not_render() {
        var t = parse(SHIPPED);
        assertThrows(IllegalArgumentException.class, () -> new Renderer().sms(t,
                Map.of("order_no", "#1042", "courier", "https://evil.example", "track_ref", "1042")));
        assertThrows(IllegalArgumentException.class, () -> new Renderer().sms(t,
                Map.of("order_no", "#1042", "courier", "डेल्हीवरी", "track_ref", "1042")));
    }
}
