package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
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
 * Razorpay Magic Checkout as a checkout source (V62): the abandoned-cart
 * webhook opens a checkout for checkout_abandon, and the Magic order closes it,
 * in natural, reversed and replayed order (CLAUDE.md invariant 11).
 */
@MicronautTest(transactional = false)
@Property(name = "engage.razorpay-magic.webhook-token", value = MagicCheckoutFlowTest.SECRET)
@Property(name = "engage.razorpay-magic.consent-copy-version", value = "magic_wa_test_v1")
@Property(name = "engage.razorpay-magic.consent-since", value = "2026-09-01")
@Property(name = "engage.consent.checkout-notice-since", value = "2026-09-01")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class MagicCheckoutFlowTest {

    static final String SECRET = "test-magic-secret-0123456789abcdef";
    static final String TOKEN = "mgc_Tz91QnRBv0aX2k";
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
                         payment_attempts, consents, identity_keys, profiles, identities CASCADE""");
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('checkout_notice_v2', 'whatsapp', 'Phone (for order updates and offers on WhatsApp/SMS)',
                        '{transactional,marketing}', 'checkout_notice'),
                       ('magic_wa_test_v1', 'whatsapp', 'Test copy: updates and offers on WhatsApp/SMS',
                        '{transactional,marketing}', 'magic_checkout')
                ON CONFLICT (version) DO NOTHING""");
        }
    }

    @Test
    void an_abandoned_magic_session_opens_a_checkout_for_the_reminder() throws Exception {
        assertEquals(200, magic(fixture()));

        assertEquals("razorpay_magic|Z2NwLXVzLWVhc3QxOjAxSkY5|919876543210|contact|priya.k@example.com|129800|"
                        + "/cart/44012345600011:2?storefront=true|open",
                q("""
                SELECT concat_ws('|', source, cart_token, phone, phone_source, email, total_paise, recovery_url,
                                 CASE WHEN completed_at IS NULL THEN 'open' ELSE 'done' END)
                  FROM checkouts WHERE token = '%s'""".formatted(TOKEN)));
        assertEquals("1", q("""
                SELECT count(*) FROM events WHERE name = 'checkout_updated'
                   AND props->>'checkout_token' = '%s' AND identity_id IS NOT NULL""".formatted(TOKEN)));
        assertEquals("Gold Snake Chain Anklet|64900", q("""
                SELECT concat_ws('|', lines->0->>'title', lines->0->>'price_paise') FROM carts
                 WHERE cart_token = 'Z2NwLXVzLWVhc3QxOjAxSkY5'"""), "the reminder can name the product");
        assertEquals("0", q("SELECT count(*) FROM consents"), "the abandoned payload carries no consent");
    }

    @Test
    void the_magic_order_then_closes_it_and_tells_the_journeys() throws Exception {
        magic(fixture());
        magicOrder();

        assertEquals(ORDER, q("SELECT order_id FROM checkouts WHERE token = '%s'".formatted(TOKEN)));
        assertEquals("1", q("""
                SELECT count(*) FROM events WHERE name = 'checkout_updated' AND dedupe_key = 'magic-closed:%s'"""
                .formatted(TOKEN)));
        assertEquals("order_Ty2gcmHGiYQwpa", q("SELECT gateway_order_id FROM orders WHERE id = '" + ORDER + "'"));
        assertEquals("order_Ty2gcmHGiYQwpa", q("""
                SELECT props->>'gateway_order_id' FROM events WHERE name = 'order_placed'"""));

        magicOrder();   // replayed order: nothing new
        assertEquals("1", q("SELECT count(*) FROM events WHERE dedupe_key LIKE 'magic-closed:%'"));
    }

    @Test
    void an_order_stored_first_means_the_late_webhook_records_a_completed_checkout() throws Exception {
        magicOrder();
        magic(fixture());

        assertEquals(ORDER + "|done", q("""
                SELECT concat_ws('|', order_id, CASE WHEN completed_at IS NULL THEN 'open' ELSE 'done' END)
                  FROM checkouts WHERE token = '%s'""".formatted(TOKEN)));
    }

    @Test
    void the_same_cart_closes_it_even_when_the_order_phone_differs() throws Exception {
        magic(fixture());
        magicOrder(json -> json.replace("+91 98765 43210", "+91 90000 11111"));

        assertEquals(ORDER, q("SELECT order_id FROM checkouts WHERE token = '%s'".formatted(TOKEN)));
    }

    @Test
    void a_replayed_webhook_is_one_checkout_and_one_event() throws Exception {
        var body = fixture();
        magic(body);
        magic(body);

        assertEquals("1", q("SELECT count(*) FROM checkouts"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'checkout_updated'"));
    }

    @Test
    void a_magic_order_records_magic_consent_and_never_the_shopify_notice_it_did_not_see() throws Exception {
        magicOrder();

        assertEquals("0", q("SELECT count(*) FROM consents WHERE source = 'checkout_notice'"));
        assertEquals("marketing|magic_wa_test_v1|magic_checkbox|unknown,"
                + "transactional|magic_wa_test_v1|dpdp_s7a_order_updates|unknown", q("""
                SELECT string_agg(concat_ws('|', purpose, copy_version, evidence->>'basis', evidence->>'pre_ticked'),
                                  ',' ORDER BY purpose DESC)
                  FROM consents WHERE source = 'magic_checkout'"""));
    }

    @Test
    void a_magic_no_records_nothing() throws Exception {
        magicOrder(json -> json.replace("\"whatsapp_sms_consent\", \"value\": \"Yes\"",
                "\"whatsapp_sms_consent\", \"value\": \"No\""));
        assertEquals("0", q("SELECT count(*) FROM consents WHERE channel = 'whatsapp'"));
    }

    @Test
    void a_wrong_secret_is_not_found_and_stores_nothing() throws Exception {
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/webhooks/magic/not-the-secret", fixture().getBytes(StandardCharsets.UTF_8))
                        .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(404, e.getStatus().getCode());
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox"));
    }

    /* -------------------------------- helpers -------------------------------- */

    static String fixture() throws java.io.IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/magic_abandoned_checkout.json"));
    }

    int magic(String json) throws Exception {
        var status = client.toBlocking().exchange(HttpRequest.POST("/webhooks/magic/" + SECRET,
                json.getBytes(StandardCharsets.UTF_8)).contentType(MediaType.APPLICATION_JSON)).getStatus().getCode();
        processor.drain();
        return status;
    }

    void magicOrder() throws Exception {
        magicOrder(json -> json);
    }

    /** The order fixture as Magic Checkout creates it: no checkout token, Magic's note attributes. */
    void magicOrder(java.util.function.UnaryOperator<String> edit) throws Exception {
        var json = Files.readString(Path.of("src/test/resources/fixtures/shopify_order_create.json"))
                .replaceFirst("(?s)\"note_attributes\"\\s*:\\s*\\[.*?\\]", """
                        "note_attributes": [
                          {"name": "Paid via", "value": "Razorpay Magic Checkout"},
                          {"name": "razorpay_order_id", "value": "order_Ty2gcmHGiYQwpa"},
                          {"name": "whatsapp_sms_consent", "value": "Yes"},
                          {"name": "cart_token", "value": "Z2NwLXVzLWVhc3QxOjAxSkY5?key=6f0c2a1b9e"}
                        ]""")
                .replaceFirst("\"checkout_token\"\\s*:\\s*\"[^\"]*\"", "\"checkout_token\": null")
                .replaceFirst("\"cart_token\"\\s*:\\s*\"[^\"]*\"", "\"cart_token\": null")
                // The session was abandoned "now"; the order follows it.
                .replaceFirst("\"created_at\"\\s*:\\s*\"2026-09-19T15:24:11\\+05:30\"",
                        "\"created_at\": \"" + java.time.OffsetDateTime.now().plusMinutes(10) + "\"");
        json = edit.apply(json);
        var body = json.getBytes(StandardCharsets.UTF_8);
        client.toBlocking().exchange(HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", "orders/create")
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
