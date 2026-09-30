package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import in.brand.engage.ingest.shopify.ShopifyInboxHandler;
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
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * WhatsApp order updates on the checkout-notice basis (checkout_notice_v1, V12).
 * The fixture order is from 2026-09-19; the notice went live 2026-09-01 here.
 */
@MicronautTest(transactional = false)
@Property(name = "engage.consent.checkout-notice-since", value = "2026-09-01")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CheckoutNoticeConsentTest {

    static final String ORDER = "6012345678901";

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("""
                TRUNCATE webhook_inbox, events, orders, pending_optins, conversions, checkouts, carts,
                         consents, identity_keys, profiles, identities CASCADE""");
            // V12 seeds this, but admin-api's tests TRUNCATE operators CASCADE, which
            // empties consent_copy_versions (created_by references operators).
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('checkout_notice_v1', 'whatsapp', 'Phone (for order and delivery updates on WhatsApp/SMS)',
                        '{transactional}', 'checkout_notice')
                ON CONFLICT (version) DO NOTHING""");
        }
    }

    @Test
    void an_order_placed_after_the_notice_went_live_gets_order_updates_only() throws Exception {
        orderCreated();

        assertEquals("whatsapp|transactional|granted|checkout_notice_v1|dpdp_s7a_order_updates|false", q("""
                SELECT concat_ws('|', channel, purpose, state, copy_version, evidence->>'basis', evidence->>'pre_ticked')
                  FROM consents WHERE source = 'checkout_notice'"""));
        assertEquals("0", q("SELECT count(*) FROM consents WHERE channel = 'whatsapp' AND purpose = 'marketing'"),
                "a notice never counts as a marketing opt-in");
    }

    @Test
    void a_replayed_order_or_a_second_order_adds_nothing() throws Exception {
        orderCreated();
        orderCreated();

        assertEquals("1", q("SELECT count(*) FROM consents WHERE source = 'checkout_notice'"));
    }

    @Test
    void a_stop_is_never_overridden_by_a_later_order() throws Exception {
        orderCreated();
        exec("""
            INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
            SELECT identity_id, 'whatsapp', 'transactional', 'withdrawn', 'wa_stop_reply', now()
              FROM orders WHERE id = '%s'""".formatted(ORDER));
        exec("DELETE FROM orders WHERE id = '" + ORDER + "'");      // the same buyer orders again

        orderCreated();

        assertEquals("withdrawn", q("""
                SELECT state FROM consent_current WHERE channel = 'whatsapp' AND purpose = 'transactional'"""));
        assertEquals("1", q("SELECT count(*) FROM consents WHERE source = 'checkout_notice'"));
    }

    @Test
    void the_start_date_is_ist_midnight_or_an_instant_and_blank_is_off() {
        assertNull(ShopifyInboxHandler.noticeSince(" "));
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00+05:30").toInstant(),
                ShopifyInboxHandler.noticeSince("2026-10-01").toInstant());
        assertEquals(OffsetDateTime.parse("2026-10-01T10:00Z"), ShopifyInboxHandler.noticeSince("2026-10-01T10:00Z"));
        assertThrows(RuntimeException.class, () -> ShopifyInboxHandler.noticeSince("01/10/2026"));
    }

    /* -------------------------------- helpers -------------------------------- */

    void orderCreated() throws Exception {
        var body = Files.readAllBytes(Path.of("src/test/resources/fixtures/shopify_order_create.json"));
        client.toBlocking().exchange(HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body)));
        processor.drain();
    }

    void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
