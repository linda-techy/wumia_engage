package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
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

/** P7-T05: Shopify's mandatory compliance webhooks, signed, through the inbox. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ComplianceWebhookTest {

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
                TRUNCATE webhook_inbox, events, orders, order_refunds, conversions, checkouts, carts, consents,
                         pending_optins, identity_keys, profiles, privacy_requests, consent_erasure_proofs CASCADE""");
            st.execute("DELETE FROM identities WHERE id <> '00000000-0000-0000-0000-000000000000'");
        }
    }

    @Test
    void customers_redact_erases_the_buyer_and_their_listed_orders() throws Exception {
        send("orders/create", Files.readString(FX.resolve("shopify_order_create.json")));
        assertEquals("1", q("SELECT count(*) FROM identity_keys WHERE value = 'priya.k@example.com'"));

        send("customers/redact", """
                {"shop_id": 954889, "shop_domain": "%s",
                 "customer": {"id": 7788001122, "email": "Priya.K@Example.com", "phone": "+91 98765 43210"},
                 "orders_to_redact": [%s]}""".formatted(System.getenv("SHOPIFY_SHOP_DOMAIN"), ORDER));

        assertEquals("0", q("SELECT count(*) FROM identities WHERE id <> '00000000-0000-0000-0000-000000000000'"));
        assertEquals("0", q("SELECT count(*) FROM identity_keys"));
        assertEquals("||129900", q("SELECT coalesce(phone,'') || '|' || coalesce(email,'') || '|' || total_paise FROM orders WHERE id = '" + ORDER + "'"),
                "contact details gone, revenue kept");
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox WHERE lower(payload::text) LIKE '%priya.k@example.com%' OR payload::text LIKE '%98765 43210%'"),
                "no raw payload still holds her email or phone, including the redact request itself");
        assertEquals("customer_redact|done|1", q("SELECT kind || '|' || status || '|' || (result->>'people') FROM privacy_requests"));
        assertEquals("1", q("SELECT count(*) FROM audit_log a JOIN privacy_requests r ON a.entity_id = 'privacy_request:' || r.id WHERE a.action = 'privacy.erase' AND a.actor_kind = 'system'"));
    }

    @Test
    void customers_redact_for_someone_we_hold_nothing_on_is_recorded_and_harmless() throws Exception {
        send("customers/redact", """
                {"shop_id": 954889, "shop_domain": "x", "customer": {"id": 1, "email": "Priya.K@Example.com", "phone": null},
                 "orders_to_redact": []}""");
        assertEquals("done|0", q("SELECT status || '|' || (result->>'people') FROM privacy_requests"));
    }

    @Test
    void a_data_request_waits_for_the_owner_and_erases_nothing() throws Exception {
        send("orders/create", Files.readString(FX.resolve("shopify_order_create.json")));
        send("customers/data_request", """
                {"shop_id": 954889, "shop_domain": "x", "orders_requested": [%s],
                 "customer": {"id": 7788001122, "email": "Priya.K@Example.com", "phone": null},
                 "data_request": {"id": 9999}}""".formatted(ORDER));
        assertEquals("data_request|open|9999|7788001122|priya.k@example.com",
                q("SELECT kind || '|' || status || '|' || external_ref || '|' || (subject->>'customer_id') || '|' || (subject->>'email') FROM privacy_requests"));
        assertEquals("1", q("SELECT count(*) FROM identity_keys WHERE value = 'priya.k@example.com'"));
    }

    @Test
    void shop_redact_opens_a_request_for_the_runbook() throws Exception {
        send("shop/redact", "{\"shop_id\": 954889, \"shop_domain\": \"x\"}");
        assertEquals("shop_redact|open", q("SELECT kind || '|' || status FROM privacy_requests"));
    }

    @Test
    void an_unsigned_compliance_webhook_is_refused_with_401() {
        var req = HttpRequest.POST("/webhooks/shopify", "{\"shop_id\": 1}".getBytes(StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", "customers/redact")
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", "bm90LXZhbGlk");
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(req));
        assertEquals(401, e.getStatus().getCode());
    }

    /** A signed webhook, as Shopify sends it, then the inbox drained. */
    void send(String topic, String json) {
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
