package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
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
 * End-to-end: signed webhook → controller → inbox → handler → Postgres.
 *
 * <p>Runs against TEST_DB_NAME from config/local.env (default engage_test).
 * Skipped when config/local.env has not been filled in. The same flow, in
 * natural, fully reversed and "late" delivery orders, was verified during
 * development against Postgres 16 with these fixtures.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class WebhookFlowTest {

    static final Path FX = Path.of("src/test/resources/fixtures");

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    String shopifySecret() { return System.getenv("SHOPIFY_API_SECRET"); }
    String shop() { return System.getenv("SHOPIFY_SHOP_DOMAIN"); }
    String razorpaySecret() { return System.getenv("RAZORPAY_WEBHOOK_SECRET"); }

    @BeforeEach
    void cleanTestDatabase() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            // Never truncate anything but a *_test database.
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean non-test database " + rs.getString(1));
            st.execute("""
                TRUNCATE webhook_inbox, events, payment_attempts, conversions, orders, checkouts, carts,
                         consents, pending_optins, identity_keys, profiles, identities CASCADE""");
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('wa_v1', 'whatsapp', 'Send me order updates and offers from WUMIKA on WhatsApp',
                        '{transactional,marketing}', 'cart')
                ON CONFLICT (version) DO NOTHING""");
        }
    }

    int shopify(String topic, String fixture) throws Exception {
        return shopifyBody(topic, Files.readAllBytes(FX.resolve(fixture)));
    }

    int shopifyBody(String topic, byte[] body) throws Exception {
        var req = HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", shop())
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(shopifySecret(), body));
        return client.toBlocking().exchange(req).code();
    }

    int razorpay(String fixture, String signature) throws Exception {
        return razorpayBody(Files.readAllBytes(FX.resolve(fixture)), signature);
    }

    int razorpayBody(byte[] body, String signature) throws Exception {
        var req = HttpRequest.POST("/webhooks/razorpay", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature != null ? signature : Hmacs.sha256Hex(razorpaySecret(), body))
                .header("x-razorpay-event-id", "evt_" + UUID.randomUUID());
        try {
            return client.toBlocking().exchange(req).code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void razorpay_failure_before_checkout_is_still_matched_and_everything_lands_on_one_identity() throws Exception {
        // Deliberately out of order: payment failure first, checkout last.
        assertEquals(200, razorpay("razorpay_payment_failed.json", null));
        assertEquals(200, shopify("orders/create", "shopify_order_create.json"));
        assertEquals(200, shopify("carts/update", "shopify_cart_update.json"));
        assertEquals(200, shopify("checkouts/update", "shopify_checkout_update.json"));
        processor.drain();

        assertEquals("failed|129900|phone_amount_window|chk_7f3a9c21b8e44d10",
                q("select status||'|'||amount_paise||'|'||match_method||'|'||checkout_token from payment_attempts"));
        assertEquals("true|6012345678901", q("select (completed_at is not null)||'|'||order_id from checkouts"));
        assertEquals("true", q("select (converted_at is not null)::text from carts"));
        assertEquals("1", q("""
                select count(distinct identity_id) from identity_keys
                 where kind in ('phone','email','shopify_customer','checkout_token','cart_token')"""));
        assertEquals("transactional=granted,marketing=granted", q("""
                select string_agg(purpose::text||'='||state, ',' order by purpose)
                  from consent_current where channel = 'whatsapp'"""));
        assertEquals("TECHNICAL", q("select props->>'failure_kind' from events where name='payment_failed_confirmed'"));
    }

    /** The fixture with the allowlisted test customer's email swapped for another value. */
    byte[] withEmail(String fixture, String email) throws Exception {
        return Files.readString(FX.resolve(fixture), StandardCharsets.UTF_8)
                .replace("Priya.K@Example.com", email).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void customers_not_on_the_allowlist_are_acknowledged_but_never_stored() throws Exception {
        // CUSTOMER_ALLOWLIST_EMAILS=priya.k@example.com in tests (build.gradle.kts).
        assertEquals(200, shopifyBody("customers/update", withEmail("shopify_customer_update.json", "someone.else@example.com")));
        assertEquals(200, shopifyBody("orders/create", withEmail("shopify_order_create.json", "someone.else@example.com")));
        assertEquals(200, razorpayBody(withEmail("razorpay_payment_failed.json", "someone.else@example.com"), null));
        // No email to check against the allowlist, but clearly a customer: dropped too.
        assertEquals(200, shopifyBody("customers/update", withEmail("shopify_customer_update.json", "")));
        processor.drain();

        assertEquals("0", q("select count(*) from webhook_inbox"));
        assertEquals("0", q("select count(*) from identities"));
    }

    @Test
    void one_foreign_email_in_a_payload_is_enough_to_drop_it() throws Exception {
        var body = Files.readString(FX.resolve("shopify_order_create.json"), StandardCharsets.UTF_8)
                .replaceFirst("Priya.K@Example.com", "someone.else@example.com")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(200, shopifyBody("orders/create", body));
        assertEquals("0", q("select count(*) from webhook_inbox"));
    }

    @Test
    void wrong_secret_is_rejected_with_401() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED.getCode(),
                razorpay("razorpay_payment_failed.json", "0000"));
    }

    @Test
    void late_order_webhook_cannot_resubscribe_someone_who_unsubscribed() throws Exception {
        assertEquals(200, shopify("customers/update", "shopify_customer_update.json"));   // unsubscribed 16:00 IST
        processor.drain();
        assertEquals(200, shopify("orders/create", "shopify_order_create.json"));         // subscribed at 15:24 IST
        processor.drain();
        assertEquals("withdrawn", q("select state::text from consent_current where channel = 'email'"));
    }
}
