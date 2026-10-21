package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Shopify's phone marketing checkbox, relabelled "Send me offers on WhatsApp
 * and SMS" on 2026-09-01 here (V42): ticked grants WhatsApp and SMS marketing,
 * unticked withdraws both; a choice from before the relabel was about SMS only.
 */
@MicronautTest(transactional = false)
@Property(name = "engage.consent.shopify-phone-marketing-since", value = "2026-09-01")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ShopifyPhoneMarketingConsentTest {

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("TRUNCATE webhook_inbox, events, consents, identity_keys, profiles, identities CASCADE");
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('shopify_phone_marketing_v1', 'whatsapp', 'Send me offers on WhatsApp and SMS',
                        '{marketing}', 'shopify_marketing_checkbox')
                ON CONFLICT (version) DO NOTHING""");
        }
    }

    @Test
    void ticking_the_box_grants_whatsapp_and_sms_marketing_and_unticking_withdraws_both() throws Exception {
        customer("subscribed", "2026-09-20T10:00:00+05:30");
        assertEquals("sms|granted|shopify_phone_marketing_v1,whatsapp|granted|shopify_phone_marketing_v1", current());

        customer("unsubscribed", "2026-09-25T10:00:00+05:30");
        assertEquals("sms|withdrawn|shopify_phone_marketing_v1,whatsapp|withdrawn|shopify_phone_marketing_v1", current());

        customer("subscribed", "2026-09-20T10:00:00+05:30");          // a late replay of the older choice
        assertEquals("sms|withdrawn|shopify_phone_marketing_v1,whatsapp|withdrawn|shopify_phone_marketing_v1", current(),
                "an older webhook never overrides a newer choice");
    }

    @Test
    void a_choice_made_under_the_old_sms_only_label_is_not_reused() throws Exception {
        customer("subscribed", "2026-08-20T10:00:00+05:30");
        assertNull(current());
    }

    @Test
    void not_subscribed_says_nothing_either_way() throws Exception {
        customer("not_subscribed", "2026-09-20T10:00:00+05:30");
        assertNull(current());
    }

    String current() throws SQLException {
        return q("""
                SELECT string_agg(concat_ws('|', channel, state, copy_version), ',' ORDER BY channel::text)
                  FROM consent_current WHERE purpose = 'marketing' AND channel IN ('whatsapp', 'sms')""");
    }

    void customer(String state, String at) throws Exception {
        var json = Files.readString(Path.of("src/test/resources/fixtures/shopify_customer_update.json"))
                .replace("\"sms_marketing_consent\": { \"state\": \"not_subscribed\" }",
                        "\"sms_marketing_consent\": { \"state\": \"" + state + "\", \"consent_updated_at\": \"" + at + "\" }");
        assertTrue(json.contains(at), "fixture edited");
        var body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.toBlocking().exchange(HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", "customers/update")
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body)));
        processor.drain();
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
