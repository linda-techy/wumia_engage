package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T06: MSG91 delivery reports, in both of MSG91's shapes, onto the send they report. */
@MicronautTest(transactional = false)
@Property(name = "engage.msg91.dlr-token", value = Msg91DlrTest.TOKEN)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class Msg91DlrTest {

    static final String TOKEN = "test-dlr-token-5c1e";
    static final String REQUEST_ID = "3763646c3058313530393138";

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    String identity;
    String sendId;

    @BeforeEach
    void setUp() throws SQLException {
        exec("TRUNCATE webhook_inbox, events, identity_keys, profiles, identities CASCADE");
        identity = UUID.randomUUID().toString();
        exec("INSERT INTO identities (id) VALUES ('" + identity + "')");
        exec("""
                INSERT INTO templates (key, channel, category, status) VALUES ('sms_order_shipped', 'sms', 'utility', 'active')
                ON CONFLICT (key) DO NOTHING""");
        exec("DELETE FROM sends WHERE provider_id = '" + REQUEST_ID + "'");
        exec("INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, provider_id, sent_at) "
                + "VALUES ('" + identity + "', 'sms', 'utility', 'sms_order_shipped', 'msg91-test-" + UUID.randomUUID()
                + "', 'sent', '" + REQUEST_ID + "', now())");
        sendId = q("SELECT id FROM sends WHERE provider_id = '" + REQUEST_ID + "'");
    }

    /** The push DLR, as MSG91 posts it: a form field holding a JSON array. */
    static String pushDlr(String status, String desc, String failedReason) {
        var json = "[{\"senderId\":\"WUMIKA\",\"requestId\":\"" + REQUEST_ID + "\",\"report\":[{\"date\":\"2026-10-10 12:06:20\","
                + "\"number\":\"919876543210\",\"status\":\"" + status + "\",\"desc\":\"" + desc + "\",\"failedReason\":\""
                + failedReason + "\"}],\"userId\":\"12345\",\"campaignName\":\"API\"}]";
        return "data=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    HttpStatus post(String body, MediaType type, String token) {
        try {
            return client.toBlocking().exchange(HttpRequest.POST("/webhooks/sms?token=" + token, body).contentType(type)).status();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    @Test void a_delivered_report_marks_the_send_delivered_and_emits_the_signal_once() throws Exception {
        assertEquals(HttpStatus.OK, post(pushDlr("1", "DELIVERED", ""), MediaType.APPLICATION_FORM_URLENCODED_TYPE, TOKEN));
        assertEquals(HttpStatus.OK, post(pushDlr("1", "DELIVERED", ""), MediaType.APPLICATION_FORM_URLENCODED_TYPE, TOKEN));
        processor.drain();

        assertEquals("delivered|2026-10-10 06:36:20", q("SELECT status || '|' || to_char(delivered_at AT TIME ZONE 'UTC', "
                + "'YYYY-MM-DD HH24:MI:SS') FROM sends WHERE id = " + sendId), "IST wall-clock time stored as UTC");
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'sms_delivered' AND props->>'send_id' = '" + sendId + "'"));
        assertEquals("1", q("SELECT count(*) FROM webhook_inbox WHERE source = 'msg91'"), "the retry was a duplicate");
    }

    @Test void a_new_webhook_failure_on_dlt_fails_the_send() throws Exception {
        var body = "{\"status\":\"2\",\"telNum\":\"919876543210\",\"senderId\":\"WUMIKA\",\"requestId\":\"" + REQUEST_ID
                + "\",\"DLT_TE_ID\":\"1107170000000000001\",\"event\":\"failed\",\"upperCaseEventName\":\"FAILED\","
                + "\"failureReason\":\"Template Inactive on DLT\"}";
        assertEquals(HttpStatus.OK, post(body, MediaType.APPLICATION_JSON_TYPE, TOKEN));
        processor.drain();

        assertEquals("failed|msg91_failed", q("SELECT status || '|' || failed_reason FROM sends WHERE id = " + sendId));
        assertEquals("msg91_failed", q("SELECT props->>'reason' FROM events WHERE name = 'sms_failed'"));
        assertEquals("0", q("SELECT count(*) FROM events WHERE name = 'sms_delivered'"));
    }

    @Test void a_report_for_a_send_we_do_not_have_changes_nothing() throws Exception {
        assertEquals(HttpStatus.OK, post(pushDlr("1", "DELIVERED", "").replace(REQUEST_ID, "ffffffffffffffffffffffff"),
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, TOKEN));
        processor.drain();
        assertEquals("sent", q("SELECT status FROM sends WHERE id = " + sendId));
        assertEquals("0", q("SELECT count(*) FROM events WHERE name LIKE 'sms_%'"));
    }

    @Test void a_wrong_token_or_an_empty_body_is_refused() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, post(pushDlr("1", "DELIVERED", ""), MediaType.APPLICATION_FORM_URLENCODED_TYPE, "nope"));
        assertEquals(HttpStatus.BAD_REQUEST, post("data=", MediaType.APPLICATION_FORM_URLENCODED_TYPE, TOKEN));
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox WHERE source = 'msg91'"));
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
