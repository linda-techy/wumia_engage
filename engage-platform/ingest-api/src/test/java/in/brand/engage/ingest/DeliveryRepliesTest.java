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

/** Replies to a failed-delivery WhatsApp become support requests for staff (2026-10-15). */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class DeliveryRepliesTest {

    static final String PHONE = MetaInboxTest.PHONE;
    static final String NDR_WAMID = "wamid.NDR1";
    static final long T0 = MetaInboxTest.T0;

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    String identity;
    String shipment;

    @BeforeEach
    void setUp() throws SQLException {
        exec("TRUNCATE webhook_inbox, events, consents, wa_inbound, channel_capability, cascade_runs, support_requests, "
                + "shipments, orders, identity_keys, profiles, identities CASCADE");
        identity = UUID.randomUUID().toString();
        exec("INSERT INTO identities (id) VALUES ('" + identity + "')");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + identity + "', 'email', 'priya.k@example.com', true)");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + identity + "', 'phone', '" + PHONE + "', true)");
        exec("INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o-1042', '#1042', '"
                + identity + "', 1249900, now())");
        shipment = q("INSERT INTO shipments (order_id, awb, carrier, status, status_at) VALUES ('o-1042', 'AWB77', 'Delhivery', "
                + "'ndr', now()) RETURNING id");
        String run = q("INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status) VALUES ('ndr_raised', '"
                + identity + "', 'shipment:" + shipment + ":ndr', 1, 'waiting') RETURNING id");
        exec("INSERT INTO templates (key, channel, category, status) VALUES ('ndr_raised_v1', 'whatsapp', 'utility', 'active') "
                + "ON CONFLICT (key) DO NOTHING");
        exec("INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, provider_id, sent_at, cascade_run_id) "
                + "VALUES ('" + identity + "', 'whatsapp', 'utility', 'ndr_raised_v1', 'ndr-test-" + UUID.randomUUID()
                + "', 'delivered', '" + NDR_WAMID + "', now(), " + run + ")");
    }

    @Test
    void a_reply_after_a_failed_delivery_opens_one_request_and_later_replies_join_it() throws Exception {
        send(MetaInboxTest.text("wamid.IN1", "Please deliver tomorrow after 5pm", T0));
        send(MetaInboxTest.text("wamid.IN2", "Flat 4B, same building", T0 + 60));

        assertEquals("delivery|open|o-1042|" + shipment + "|2", q("""
                SELECT kind || '|' || status || '|' || order_id || '|' || shipment_id || '|' || cardinality(message_ids)
                  FROM support_requests"""));
        assertEquals("Please deliver tomorrow after 5pm\nFlat 4B, same building", q("SELECT message_text FROM support_requests"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'delivery_reply'"), "the customer is acknowledged once");
    }

    @Test
    void the_same_message_twice_is_one_entry() throws Exception {
        var msg = MetaInboxTest.text("wamid.IN1", "Tomorrow please", T0);
        send(msg);
        send(msg.replace("\"P\"}", "\"P\" }"));
        assertEquals("1", q("SELECT cardinality(message_ids) FROM support_requests"));
    }

    @Test
    void without_a_recent_failed_delivery_a_message_keeps_no_text() throws Exception {
        exec("UPDATE sends SET created_at = now() - interval '10 days' WHERE provider_id = '" + NDR_WAMID + "'");
        send(MetaInboxTest.text("wamid.IN9", "Hello", T0 + 6 * 86_400L));
        assertEquals("0", q("SELECT count(*) FROM support_requests"));
    }

    void send(String json) {
        var body = json.getBytes(StandardCharsets.UTF_8);
        var req = HttpRequest.POST("/meta/webhook", body).contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", "sha256=" + Hmacs.sha256Hex("test-meta-app-secret", body));
        assertEquals(200, client.toBlocking().exchange(req).code());
        processor.drain();
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
