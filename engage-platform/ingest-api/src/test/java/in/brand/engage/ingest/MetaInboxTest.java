package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T04: WhatsApp statuses and inbound messages through the signed webhook and the inbox. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class MetaInboxTest {

    static final String PHONE = "919876543210";
    static final String WAMID = "wamid.TEST1";
    static final long T0 = 1_791_500_000L;                                   // 2026-10-09, ~ 11:43 IST

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    String identity;

    @BeforeEach
    void setUp() throws SQLException {
        exec("TRUNCATE webhook_inbox, events, consents, spend_ledger, wa_inbound, channel_capability, cascade_runs, "
                + "identity_keys, profiles, identities CASCADE");
        identity = UUID.randomUUID().toString();
        exec("INSERT INTO identities (id) VALUES ('" + identity + "')");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + identity + "', 'email', 'priya.k@example.com', true)");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + identity + "', 'phone', '" + PHONE + "', true)");
        exec("""
                INSERT INTO templates (key, channel, category, status) VALUES ('order_shipped_v1', 'whatsapp', 'utility', 'active')
                ON CONFLICT (key) DO UPDATE SET status = 'active'""");
        exec("INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, provider_id, sent_at) "
                + "VALUES ('" + identity + "', 'whatsapp', 'utility', 'order_shipped_v1', 'meta-test-" + UUID.randomUUID()
                + "', 'sent', '" + WAMID + "', now())");
    }

    /* ------------------------------ payloads ------------------------------ */

    static String status(String wamid, String status, long ts, String extra) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"P"},
                 "statuses":[{"id":"%s","status":"%s","timestamp":"%d","recipient_id":"%s"%s}]}}]}]}"""
                .formatted(wamid, status, ts, PHONE, extra);
    }

    static String pricing(String category) {
        return ",\"pricing\":{\"billable\":true,\"pricing_model\":\"PMP\",\"category\":\"" + category + "\"}";
    }

    static String text(String id, String body, long ts) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"P"},
                 "contacts":[{"profile":{"name":"Priya"},"wa_id":"%s"}],
                 "messages":[{"from":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]}}]}]}"""
                .formatted(PHONE, PHONE, id, ts, body);
    }

    void send(String json) {
        var body = json.getBytes(StandardCharsets.UTF_8);
        var req = HttpRequest.POST("/meta/webhook", body).contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", "sha256=" + Hmacs.sha256Hex("test-meta-app-secret", body));
        assertEquals(200, client.toBlocking().exchange(req).code());
        processor.drain();
    }

    /* ------------------------------- statuses ------------------------------- */

    @Test
    void delivered_marks_the_send_and_the_number_capable_and_books_spend_once() throws Exception {
        var delivered = status(WAMID, "delivered", T0, pricing("utility"));
        send(delivered);
        send(delivered.replace("\"P\"}", "\"P\" }"));                    // same status, different bytes: a re-send

        assertEquals("delivered", q("SELECT status FROM sends WHERE provider_id = '" + WAMID + "'"));
        assertEquals("CAPABLE", q("SELECT state FROM channel_capability WHERE identity_id = '" + identity + "'"));
        assertEquals("1|12", q("SELECT messages || '|' || paise FROM spend_ledger WHERE channel = 'whatsapp' AND category = 'utility'"),
                "booked once, at the V8 utility rate");
    }

    @Test
    void read_before_delivered_ends_read_and_a_late_sent_never_un_reads() throws Exception {
        send(status(WAMID, "read", T0 + 20, ""));
        send(status(WAMID, "delivered", T0 + 10, ""));
        send(status(WAMID, "sent", T0, ""));
        assertEquals("read|true|true", q("SELECT status || '|' || (delivered_at IS NOT NULL) || '|' || (read_at IS NOT NULL) "
                + "FROM sends WHERE provider_id = '" + WAMID + "'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'wa_read'"));
    }

    @Test
    void a_131026_failure_fails_the_send_and_records_one_capability_strike() throws Exception {
        send(status(WAMID, "failed", T0, ",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\"}]"));
        assertEquals("failed|wa_131026", q("SELECT status || '|' || failed_reason FROM sends WHERE provider_id = '" + WAMID + "'"));
        assertEquals("UNKNOWN|1", q("SELECT state || '|' || cardinality(strike_days) FROM channel_capability WHERE identity_id = '" + identity + "'"));
        assertEquals("131026", q("SELECT props->>'code' FROM events WHERE name = 'wa_failed'"));
    }

    @Test
    void a_utility_send_billed_as_marketing_books_marketing_spend() throws Exception {
        send(status(WAMID, "delivered", T0, pricing("marketing")));
        assertEquals("marketing|86", q("SELECT billed_category || '|' || cost_paise FROM sends WHERE provider_id = '" + WAMID + "'"));
        assertEquals("86", q("SELECT paise FROM spend_ledger WHERE channel = 'whatsapp' AND category = 'marketing'"));
    }

    /* -------------------------------- inbound -------------------------------- */

    @Test
    void stop_reply_propagates_to_all_marketing_channels() throws Exception {
        exec("INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status) VALUES "
                + "('cart_recovery', '" + identity + "', 'cart-1', 3, 'waiting')");
        var stop = text("wamid.IN.STOP", "band karo", T0);
        send(stop);
        send(stop.replace("\"P\"}", "\"P\" }"));                            // Meta re-sends it

        assertEquals("email/marketing,push/marketing,sms/marketing,whatsapp/marketing,whatsapp/transactional", q("""
                SELECT string_agg(channel || '/' || purpose, ',' ORDER BY channel::text, purpose::text)
                  FROM consent_current WHERE identity_id = '%s' AND state = 'withdrawn'""".formatted(identity)));
        assertEquals("5", q("SELECT count(*) FROM consents WHERE source = 'wa_stop_reply'"), "one withdrawal set");
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'wa_stop'"), "one confirmation");
        assertEquals("cancelled|stopped", q("SELECT status || '|' || outcome FROM cascade_runs WHERE subject_key = 'cart-1'"));
    }

    @Test
    void stop_is_understood_in_english_hinglish_and_hindi() throws Exception {
        int n = 0;
        for (var word : new String[] {"STOP ", "Band Karo", "बंद करो", "unsubscribe."}) {
            send(text("wamid.IN.S" + n++, word, T0 + n));
        }
        assertEquals(String.valueOf(n), q("SELECT count(*) FROM wa_inbound WHERE action = 'stop'"));
        send(text("wamid.IN.X", "please stop sending the wrong size", T0 + 99));
        assertEquals(null, q("SELECT action FROM wa_inbound WHERE message_id = 'wamid.IN.X'"), "a sentence is not STOP");
    }

    @Test
    void any_inbound_message_opens_the_service_window_and_proves_whatsapp() throws Exception {
        send(text("wamid.IN.Q", "Where is my order?", T0));
        assertEquals("CAPABLE", q("SELECT state FROM channel_capability WHERE identity_id = '" + identity + "'"));
        assertEquals("24", q("SELECT (round(extract(epoch FROM wa_window_until) - " + T0 + ") / 3600)::int FROM profiles WHERE identity_id = '" + identity + "'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'wa_message_received'"));
        assertNull(q("SELECT props->>'text' FROM events WHERE name = 'wa_message_received'"), "message text is not stored");
    }

    @Test
    void start_after_stop_grants_whatsapp_again() throws Exception {
        send(text("wamid.IN.STOP2", "stop", T0));
        send(text("wamid.IN.START", "shuru karo", T0 + 60));
        assertEquals("granted|granted", q("""
                SELECT string_agg(state::text, '|' ORDER BY purpose) FROM consent_current
                 WHERE identity_id = '%s' AND channel = 'whatsapp'""".formatted(identity)));
        assertEquals("withdrawn", q("SELECT state FROM consent_current WHERE identity_id = '" + identity + "' AND channel = 'push'"),
                "START on WhatsApp does not re-subscribe other channels");
    }

    /* ------------------------ templates and numbers ------------------------ */

    static String change(String field, String value) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"%s","value":%s}]}]}"""
                .formatted(field, value);
    }

    void waTemplateRow() throws SQLException {
        exec("""
                INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                VALUES ('order_shipped_v1', 'en', 'order_shipped_v1', 'utility', 'utility', 'APPROVED', 'GREEN')
                ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
    }

    @Test
    void a_red_quality_webhook_pauses_the_template_before_the_next_decision() throws Exception {
        waTemplateRow();
        send(change("message_template_quality_update", """
                {"previous_quality_score":"GREEN","new_quality_score":"RED","message_template_id":1,
                 "message_template_name":"order_shipped_v1","message_template_language":"en"}"""));
        assertEquals("RED", q("SELECT quality FROM wa_templates WHERE provider_name = 'order_shipped_v1' AND language = 'en'"));
        assertEquals("paused", q("SELECT status FROM templates WHERE key = 'order_shipped_v1'"));
    }

    @Test
    void a_rejection_is_recorded_with_its_reason() throws Exception {
        waTemplateRow();
        send(change("message_template_status_update", """
                {"event":"REJECTED","message_template_id":1,"message_template_name":"order_shipped_v1",
                 "message_template_language":"en","reason":"INCORRECT_CATEGORY"}"""));
        assertEquals("REJECTED|INCORRECT_CATEGORY",
                q("SELECT status || '|' || rejected_reason FROM wa_templates WHERE provider_name = 'order_shipped_v1'"));
    }

    @Test
    void a_flagged_number_is_recorded_with_history_and_raised() throws Exception {
        exec("""
                INSERT INTO wa_phone_numbers (phone_number_id, display_number, quality, messaging_tier)
                VALUES ('PN1', '919000000000', 'GREEN', 'TIER_1K') ON CONFLICT (phone_number_id) DO UPDATE SET quality = 'GREEN'""");
        exec("DELETE FROM wa_phone_number_history WHERE phone_number_id = 'PN1'");
        send(change("phone_number_quality_update", """
                {"display_phone_number":"919000000000","event":"FLAGGED","current_limit":"TIER_1K"}"""));
        assertEquals("RED", q("SELECT quality FROM wa_phone_numbers WHERE phone_number_id = 'PN1'"));
        assertEquals("FLAGGED", q("SELECT event FROM wa_phone_number_history WHERE phone_number_id = 'PN1'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'wa_number_flagged'"));
    }

    void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            st.execute(sql);
        }
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
