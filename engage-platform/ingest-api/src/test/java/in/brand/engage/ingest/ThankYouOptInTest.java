package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.http.HttpMethod;
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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P2-T05: the Thank you page WhatsApp opt-in, end to end against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ThankYouOptInTest {

    static final String ORDER = "6012345678901";
    static final String GID = "gid://shopify/OrderIdentity/" + ORDER;

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
                TRUNCATE webhook_inbox, events, orders, order_refunds, pending_optins, conversions, checkouts, carts,
                         consents, pending_optins, identity_keys, profiles, identities CASCADE""");
            // V12 seeds this, but admin-api's tests TRUNCATE operators CASCADE, which
            // empties consent_copy_versions (created_by references operators).
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('ty_wa_v1', 'whatsapp',
                        'Get order updates, new arrivals and offers from WUMIKA on WhatsApp. Reply STOP anytime to opt out.',
                        '{transactional,marketing}', 'thank_you')
                ON CONFLICT (version) DO NOTHING""");
        }
    }

    @Test
    void an_opt_in_before_the_order_is_kept_and_applied_when_the_order_lands() throws Exception {
        assertEquals(HttpStatus.ACCEPTED, optIn(token(), GID, "ty_wa_v1"));
        assertEquals("0", q("SELECT count(*) FROM consents WHERE source = 'thank_you'"));
        assertEquals("f", q("SELECT (applied_at IS NOT NULL)::text::char FROM pending_optins WHERE order_id = '" + ORDER + "'"));

        orderCreated();

        assertEquals("whatsapp|marketing|granted|ty_wa_v1,whatsapp|transactional|granted|ty_wa_v1", thankYouConsent());
        assertEquals("t", q("SELECT (applied_at IS NOT NULL)::text::char FROM pending_optins WHERE order_id = '" + ORDER + "'"));
    }

    @Test
    void an_opt_in_after_the_order_is_applied_at_once_to_the_order_phone() throws Exception {
        orderCreated();

        assertEquals(HttpStatus.OK, optIn(token(), GID, "ty_wa_v1"));

        assertEquals("whatsapp|marketing|granted|ty_wa_v1,whatsapp|transactional|granted|ty_wa_v1", thankYouConsent());
        assertEquals(q("SELECT identity_id::text FROM orders WHERE id = '" + ORDER + "'"),
                q("SELECT DISTINCT identity_id::text FROM consents WHERE source = 'thank_you'"), "the order's buyer, not the request");
    }

    @Test
    void a_replayed_click_records_one_consent() throws Exception {
        orderCreated();
        optIn(token(), GID, "ty_wa_v1");
        optIn(token(), GID, "ty_wa_v1");
        optIn(token(), ORDER, "ty_wa_v1");        // the bare number is the same order

        assertEquals("2", q("SELECT count(*) FROM consents WHERE source = 'thank_you'"), "one row per purpose, once");
    }

    @Test
    void a_bad_token_or_unregistered_copy_records_nothing() throws Exception {
        orderCreated();

        assertEquals(HttpStatus.UNAUTHORIZED, optIn(null, GID, "ty_wa_v1"));
        assertEquals(HttpStatus.UNAUTHORIZED, optIn(token().replace('.', ',').replaceFirst(",", "."), GID, "ty_wa_v1"));
        assertEquals(HttpStatus.UNAUTHORIZED, optIn(signed("{\"alg\":\"none\"}", claims(60)), GID, "ty_wa_v1"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, optIn(token(), GID, "wa_made_up_v9"));
        assertEquals(HttpStatus.BAD_REQUEST, optIn(token(), "order-42", "ty_wa_v1"));

        assertEquals("0", q("SELECT count(*) FROM consents WHERE source = 'thank_you'"));
        assertEquals("0", q("SELECT count(*) FROM pending_optins"));
    }

    @Test
    void only_the_checkout_extension_origin_may_call_it_cross_origin() {
        var ok = client.toBlocking().exchange(preflight("https://extensions.shopifycdn.com"));
        assertEquals("https://extensions.shopifycdn.com", ok.getHeaders().get("Access-Control-Allow-Origin"));

        HttpStatus other;
        String allowed;
        try {
            var res = client.toBlocking().exchange(preflight("https://evil.example"));
            other = res.status();
            allowed = res.getHeaders().get("Access-Control-Allow-Origin");
        } catch (HttpClientResponseException e) {
            other = e.getStatus();
            allowed = e.getResponse().getHeaders().get("Access-Control-Allow-Origin");
        }
        assertNull(allowed, "another origin gets no CORS grant (status " + other + ")");
    }

    /* -------------------------------- helpers -------------------------------- */

    HttpRequest<?> preflight(String origin) {
        return HttpRequest.create(HttpMethod.OPTIONS, "/shopify/thankyou/whatsapp-optin")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type");
    }

    HttpStatus optIn(String token, String orderId, String copy) {
        var req = HttpRequest.POST("/shopify/thankyou/whatsapp-optin",
                        "{\"orderId\":\"" + orderId + "\",\"copyVersion\":\"" + copy + "\",\"phone\":\"+919999999999\"}")
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) req.bearerAuth(token);
        try {
            return client.toBlocking().exchange(req).status();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    /** A session token as Shopify's checkout would mint it for this app and shop. */
    static String token() {
        return signed("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims(60));
    }

    static String claims(long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        var shop = System.getenv("SHOPIFY_SHOP_DOMAIN");
        return """
            {"iss":"https://%s/admin","dest":"https://%s","aud":"%s","exp":%d,"nbf":%d,"iat":%d,"jti":"%s"}"""
                .formatted(shop, shop, System.getenv("SHOPIFY_CLIENT_ID"), now + ttlSeconds, now, now, UUID.randomUUID());
    }

    static String signed(String header, String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        var hp = enc.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return hp + "." + enc.encodeToString(Hmacs.sha256(System.getenv("SHOPIFY_API_SECRET"),
                hp.getBytes(StandardCharsets.US_ASCII)));
    }

    void orderCreated() throws Exception {
        var body = Files.readAllBytes(Path.of("src/test/resources/fixtures/shopify_order_create.json"));
        client.toBlocking().exchange(HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body)));
        processor.drain();
        assertNotNull(q("SELECT phone FROM orders WHERE id = '" + ORDER + "'"), "fixture order has a phone");
    }

    String thankYouConsent() throws SQLException {
        return q("""
                SELECT string_agg(concat_ws('|', channel, purpose, state, copy_version), ',' ORDER BY purpose DESC)
                  FROM consents WHERE source = 'thank_you'""");
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
