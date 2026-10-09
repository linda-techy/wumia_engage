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
 * P1-T04 topics through the signed webhook endpoint and the inbox, against
 * Postgres, in natural, reversed and replayed order (invariant 11).
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ShopifyLifecycleTest {

    static final Path FX = Path.of("src/test/resources/fixtures");
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
                TRUNCATE webhook_inbox, events, orders, order_refunds, variant_prices, products, conversions, checkouts, carts,
                         consents, pending_optins, identity_keys, profiles, identities CASCADE""");
        }
    }

    /* -------------------------------- orders -------------------------------- */

    @Test
    void a_cancellation_after_the_order_marks_it_once_and_places_nothing_new() throws Exception {
        send("orders/create", fixture("shopify_order_create.json"));
        send("orders/cancelled", fixture("shopify_order_cancelled.json"));
        send("orders/cancelled", fixture("shopify_order_cancelled.json"));     // Shopify re-sends

        assertEquals("customer|voided", q("SELECT cancel_reason || '|' || financial_status FROM orders WHERE id = '" + ORDER + "'"));
        assertNotNull(q("SELECT cancelled_at::text FROM orders WHERE id = '" + ORDER + "'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'order_cancelled'"), "one event per order");
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'order_placed'"), "only orders/create places");
    }

    @Test
    void a_cancellation_before_the_order_still_lands_and_stays() throws Exception {
        send("orders/cancelled", fixture("shopify_order_cancelled.json"));
        send("orders/create", fixture("shopify_order_create.json"));

        assertNotNull(q("SELECT cancelled_at::text FROM orders WHERE id = '" + ORDER + "'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'order_cancelled'"));
    }

    @Test
    void paid_records_the_status_but_never_undoes_a_cancellation() throws Exception {
        var pending = fixture("shopify_order_create.json").replace("\"financial_status\": \"paid\"", "\"financial_status\": \"pending\"");
        send("orders/create", pending);
        send("orders/paid", fixture("shopify_order_create.json"));
        assertEquals("paid", q("SELECT financial_status FROM orders WHERE id = '" + ORDER + "'"));

        send("orders/cancelled", fixture("shopify_order_cancelled.json"));
        send("orders/paid", fixture("shopify_order_create.json"));          // late paid after the cancel
        assertEquals("voided", q("SELECT financial_status FROM orders WHERE id = '" + ORDER + "'"));
        assertEquals("0", q("SELECT count(*) FROM events WHERE name = 'order_paid'"), "paid emits no event");
    }

    /* -------------------------------- refunds -------------------------------- */

    @Test
    void a_refund_replayed_twice_counts_once_and_only_successful_money() throws Exception {
        send("orders/create", fixture("shopify_order_create.json"));
        send("refunds/create", fixture("shopify_refund_create.json"));
        send("refunds/create", fixture("shopify_refund_create.json"));

        assertEquals("49900", q("SELECT refunded_paise FROM orders WHERE id = '" + ORDER + "'"),
                "₹499.00 succeeded; the failed ₹100 transaction refunds nothing");
        assertEquals("1", q("SELECT count(*) FROM order_refunds"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'refund_initiated'"));
        assertNotNull(q("SELECT identity_id::text FROM events WHERE name = 'refund_initiated'"), "joined to the buyer");
    }

    @Test
    void a_refund_before_its_order_is_folded_in_when_the_order_lands() throws Exception {
        send("refunds/create", fixture("shopify_refund_create.json"));
        send("orders/create", fixture("shopify_order_create.json"));

        assertEquals("49900", q("SELECT refunded_paise FROM orders WHERE id = '" + ORDER + "'"));
    }

    /* -------------------------------- prices -------------------------------- */

    @Test
    void a_price_fall_emits_price_dropped_with_both_prices_and_a_rise_emits_nothing() throws Exception {
        send("products/update", product("1899.00", "2026-09-20T10:00:00Z"));
        assertEquals("0", q("SELECT count(*) FROM events WHERE name = 'price_dropped'"), "first sighting is a baseline");

        send("products/update", product("1499.00", "2026-09-21T10:00:00Z"));
        assertEquals("189900|149900|Linen Kurta|linen-kurta|M", q("""
                SELECT concat_ws('|', props->>'old_price_paise', props->>'new_price_paise', props->>'product_title',
                                 props->>'product_handle', props->>'variant_title')
                  FROM events WHERE name = 'price_dropped'"""));

        send("products/update", product("1599.00", "2026-09-22T10:00:00Z"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'price_dropped'"), "a rise is not a drop");
        assertEquals("159900", q("SELECT price_paise FROM variant_prices WHERE variant_id = '44581230001'"));
    }

    @Test
    void a_replayed_or_late_price_update_changes_nothing() throws Exception {
        send("products/update", product("1899.00", "2026-09-20T10:00:00Z"));
        send("products/update", product("1499.00", "2026-09-21T10:00:00Z"));
        send("products/update", product("1499.00", "2026-09-21T10:00:00Z"));   // replay
        send("products/update", product("999.00", "2026-09-19T10:00:00Z"));    // older than what we hold

        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'price_dropped'"));
        assertEquals("149900", q("SELECT price_paise FROM variant_prices WHERE variant_id = '44581230001'"));
    }

    @Test
    void an_unreadable_price_skips_that_variant_and_keeps_the_rest() throws Exception {
        send("products/update", product("12.345", "2026-09-20T10:00:00Z"));

        assertEquals("0", q("SELECT count(*) FROM variant_prices WHERE variant_id = '44581230001'"));
        assertEquals("true", q("SELECT bool_and(processed_at IS NOT NULL)::text FROM webhook_inbox"));
    }

    /* ----------------------- segment sources (P6-T05) ----------------------- */

    @Test
    void an_orders_lines_are_written_once_whichever_order_topic_arrives_first() throws Exception {
        send("orders/paid", fixture("shopify_order_create.json"));
        send("orders/create", fixture("shopify_order_create.json"));
        send("orders/create", fixture("shopify_order_create.json"));          // Shopify re-sends

        assertEquals("1", q("SELECT count(*) FROM order_lines WHERE order_id = '" + ORDER + "'"));
        assertEquals("8801|44581230001|M|1|129900", q("""
                SELECT product_id || '|' || variant_id || '|' || variant_title || '|' || quantity || '|' || price_paise
                  FROM order_lines WHERE order_id = '%s'""".formatted(ORDER)));
    }

    @Test
    void a_products_type_tags_and_size_option_are_kept_and_a_late_update_never_wins() throws Exception {
        send("products/update", catalogProduct("Kurta", " Cotton, Festive ", "2026-09-21T10:00:00Z"));
        send("products/update", catalogProduct("Dress", "old", "2026-09-20T10:00:00Z"));   // older, arrives late

        assertEquals("kurta|{cotton,festive}|1|linen-kurta", q("""
                SELECT product_type || '|' || tags::text || '|' || size_position || '|' || handle
                  FROM products WHERE product_id = '8801'"""));
    }

    @Test
    void customer_tags_are_kept_lower_case_on_the_profile() throws Exception {
        send("customers/update", fixture("shopify_customer_update.json"));

        assertEquals("[\"vip\", \"ethnic\"]",
                q("SELECT attrs->>'shopify_tags' FROM profiles WHERE jsonb_exists(attrs, 'shopify_tags')"));
    }

    static String catalogProduct(String type, String tags, String updatedAt) {
        return """
            {"id": 8801, "title": "Linen Kurta", "handle": "linen-kurta", "product_type": "%s", "tags": "%s",
             "updated_at": "%s",
             "options": [{"name": "Size", "position": 1, "values": ["S", "M"]}, {"name": "Colour", "position": 2}],
             "variants": [{"id": 44581230001, "title": "M", "price": "1299.00"}]}""".formatted(type, tags, updatedAt);
    }

    /* ------------------------------- uninstall ------------------------------- */

    @Test
    void uninstall_is_recorded_for_alerting() throws Exception {
        send("app/uninstalled", "{\"id\": 1, \"name\": \"wumika-dev\", \"domain\": \"wumika-dev.myshopify.com\"}");

        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'app_uninstalled'"));
    }

    /* -------------------------------- helpers -------------------------------- */

    static String product(String price, String updatedAt) {
        return """
            {"id": 8801, "title": "Linen Kurta", "handle": "linen-kurta", "updated_at": "%s",
             "variants": [{"id": 44581230001, "title": "M", "price": "%s"}]}""".formatted(updatedAt, price);
    }

    static String fixture(String name) throws Exception {
        return Files.readString(FX.resolve(name));
    }

    /** A signed webhook, as Shopify sends it, then the inbox drained. */
    void send(String topic, String json) throws Exception {
        var body = json.getBytes(StandardCharsets.UTF_8);
        var req = HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body));
        assertEquals(200, client.toBlocking().exchange(req).code());
        processor.drain();
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
