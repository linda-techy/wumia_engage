package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The rows {@code worker shopify-customers-sync} queues (owner, 2026-11-04):
 * Shopify's WhatsApp setting on the phone number becomes WhatsApp marketing
 * consent, with who recorded it; Shopify's lifetime order history goes on the
 * profile for the segments.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ShopifyCustomerSyncTest {

    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("TRUNCATE webhook_inbox, events, consents, identity_keys, profiles, identities CASCADE");
        }
    }

    @Test
    void whatsapp_subscribed_in_shopify_grants_whatsapp_marketing_and_the_history_reaches_the_profile() throws Exception {
        queue("1", "subscribed", "2026-11-03T18:00:00Z", "SHOPIFY", "2026-11-03T18:00:00Z");
        assertEquals("whatsapp|granted|shopify_whatsapp|SHOPIFY", q("""
                SELECT concat_ws('|', channel, state, source, (SELECT evidence->>'collected_from' FROM consents
                                                               WHERE channel = 'whatsapp' LIMIT 1))
                  FROM consent_current WHERE purpose = 'marketing' AND channel = 'whatsapp'"""));
        assertEquals("Asha|3|t|459900|kochi|KL", q("""
                SELECT concat_ws('|', attrs->>'first_name', attrs->>'shopify_orders_count',
                                 (attrs->>'shopify_last_order_at')::timestamptz = '2026-06-01T10:00:00Z',
                                 attrs->>'shopify_spent_paise',
                                 lower(attrs->>'city'), attrs->>'state')
                  FROM profiles"""));
        assertEquals("919876500001", q("SELECT value FROM identity_keys WHERE kind = 'phone'"));
    }

    @Test
    void unsubscribed_later_withdraws_and_a_rerun_of_the_same_customer_adds_nothing() throws Exception {
        queue("1", "subscribed", "2026-11-03T18:00:00Z", "SHOPIFY", "2026-11-03T18:00:00Z");
        queue("1", "subscribed", "2026-11-03T18:00:00Z", "SHOPIFY", "2026-11-03T18:00:00Z");   // same delivery id
        assertEquals("1", q("SELECT count(*) FROM consents WHERE channel = 'whatsapp'"));

        queue("1", "unsubscribed", "2026-11-04T09:00:00Z", "SHOPIFY", "2026-11-04T09:00:00Z");
        assertEquals("withdrawn", q("SELECT state FROM consent_current WHERE channel = 'whatsapp' AND purpose = 'marketing'"));
    }

    @Test
    void not_subscribed_records_nothing() throws Exception {
        queue("1", "not_subscribed", null, null, "2026-11-03T18:00:00Z");
        assertNull(q("SELECT state FROM consent_current WHERE channel = 'whatsapp' AND purpose = 'marketing'"));
        assertNotNull(q("SELECT 1 FROM profiles"), "the customer is still known, for order updates");
    }

    /** A row as CustomerSync.store writes it. */
    void queue(String id, String waState, String waAt, String collectedFrom, String updatedAt) throws Exception {
        var payload = """
                {"id": "%s", "first_name": "Asha", "updated_at": "%s", "tags": "VIP",
                 "email": "%s", "email_marketing_consent": {"state": "not_subscribed"},
                 "phone": "+919876500001", "sms_marketing_consent": {"state": "not_subscribed"},
                 "whatsapp_marketing_consent": {"state": "%s", "consent_updated_at": %s, "consent_collected_from": %s},
                 "orders_count": 3, "last_order_at": "2026-06-01T10:00:00Z", "total_spent": "4599.00",
                 "default_address": {"city": "Kochi", "province_code": "KL"},
                 "_engage_source": "shopify-customers-sync"}""".formatted(id, updatedAt, allowlisted(), waState,
                waAt == null ? "null" : "\"" + waAt + "\"", collectedFrom == null ? "null" : "\"" + collectedFrom + "\"");
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement("""
                INSERT INTO webhook_inbox (source, delivery_id, topic, payload)
                VALUES ('shopify', ?, 'customers/update', ?::jsonb) ON CONFLICT DO NOTHING""")) {
            ps.setString(1, "sync:customer:" + id + ":" + updatedAt);
            ps.setString(2, payload);
            ps.executeUpdate();
        }
        processor.drain();
    }

    static String allowlisted() {
        var raw = System.getenv().getOrDefault("CUSTOMER_ALLOWLIST_EMAILS", "");
        var first = raw.split(",")[0].strip();
        return first.isEmpty() || first.equals("*") ? "asha@example.com" : first;
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
