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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * V54: "Get my code on WhatsApp". The storefront asks for a ref; the shopper's
 * own WhatsApp message brings it back, which links their number to the browser,
 * records their opt-in and asks the worker for the code.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class WaOfferTest {

    static final String PHONE = "919812345678";

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    String anon;
    UUID browser;

    @BeforeEach
    void clean() throws SQLException {
        anon = UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean non-test database " + rs.getString(1));
            st.execute("""
                TRUNCATE webhook_inbox, wa_inbound, wa_messages, wa_threads, wa_link_tokens, push_prompt_events, consents, events,
                         carts, channel_capability, identity_keys, profiles, identities CASCADE""");
            // admin-api's tests TRUNCATE operators CASCADE, which empties consent_copy_versions.
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('wa_offer_v1', 'whatsapp', 'Opens WhatsApp with a message to WUMIKA. By sending it you agree to get your code, order updates and offers on WhatsApp. Reply STOP anytime.', '{transactional,marketing}', 'wa_thread')
                ON CONFLICT (version) DO NOTHING""");
            var id = st.executeQuery("SELECT resolve_identity('[{\"kind\":\"anon\",\"value\":\"" + anon + "\",\"verified\":false}]'::jsonb)");
            id.next();
            browser = id.getObject(1, UUID.class);
            st.execute("INSERT INTO carts (cart_token, identity_id, item_count, total_paise, updated_at) VALUES ('cart-wa-1', NULL, 1, 149900, now())");
            // Tests run with the pre-release allowlist (priya.k@example.com): the number must be hers for Meta's
            // webhook to be kept, as for a known customer who has not linked this browser yet.
            st.execute("""
                SELECT resolve_identity('[{"kind":"email","value":"priya.k@example.com","verified":true},
                                          {"kind":"phone","value":"%s","verified":true}]'::jsonb)""".formatted(PHONE));
        }
    }

    String signedUri(String path) {
        var q = new TreeMap<String, String>();
        q.put("shop", System.getenv("SHOPIFY_SHOP_DOMAIN"));
        q.put("path_prefix", "/apps/push");
        q.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        q.put("logged_in_customer_id", "");
        var message = new StringBuilder();
        q.forEach((k, v) -> message.append(k).append('=').append(v));
        var signature = Hmacs.sha256Hex(System.getenv("SHOPIFY_API_SECRET"), message.toString().getBytes(StandardCharsets.UTF_8));
        var uri = new StringBuilder("/shopify/proxy").append(path).append('?');
        q.forEach((k, v) -> uri.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return uri.append("signature=").append(signature).toString();
    }

    void whatsapp(String id, String text) {
        var json = """
                {"object":"whatsapp_business_account","entry":[{"id":"W","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"P"},
                 "contacts":[{"profile":{"name":"Anika Rao"},"wa_id":"%s"}],
                 "messages":[{"from":"%s","id":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]}}]}]}"""
                .formatted(PHONE, PHONE, id, Instant.now().getEpochSecond(), text);
        var body = json.getBytes(StandardCharsets.UTF_8);
        var req = HttpRequest.POST("/meta/webhook", body).contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", "sha256=" + Hmacs.sha256Hex("test-meta-app-secret", body));
        assertEquals(200, client.toBlocking().exchange(req).code());
        processor.drain();
    }

    Object one(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    @Test
    void the_shoppers_own_message_links_their_number_records_the_opt_in_and_asks_for_the_code() throws SQLException {
        var link = client.toBlocking().retrieve(HttpRequest.POST(signedUri("/wa-link"), Map.of("anonId", anon, "cartToken", "cart-wa-1")), Map.class);
        var ref = (String) link.get("ref");
        assertTrue(ref.matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}"), ref);
        assertEquals("15557963126", link.get("number"), "the number Engage sends from (Settings default)");
        assertTrue(((String) link.get("message")).endsWith("(ref " + ref + ")"), (String) link.get("message"));
        assertTrue(((String) link.get("copy")).startsWith("Opens WhatsApp with a message to WUMIKA."));
        var again = client.toBlocking().retrieve(HttpRequest.POST(signedUri("/wa-link"), Map.of("anonId", anon)), Map.class);
        assertEquals(ref, again.get("ref"), "the same unused ref within a day");

        whatsapp("wamid.OFFER1", (String) link.get("message"));

        var customer = (UUID) one("SELECT identity_id FROM identity_keys WHERE kind = 'phone' AND value = ?", PHONE);
        assertEquals(customer, one("SELECT identity_id FROM identity_keys WHERE kind = 'anon' AND value = ?", anon), "browser and number are one person");
        assertEquals(customer, one("SELECT identity_id FROM carts WHERE cart_token = 'cart-wa-1'"), "their bag can be recovered on WhatsApp");
        assertEquals("granted|wa_offer_link|wa_offer_v1", one("""
                SELECT state || '|' || source || '|' || copy_version FROM consent_current
                 WHERE identity_id = ? AND channel = 'whatsapp' AND purpose = 'marketing'""", customer));
        assertEquals(1L, ((Number) one("SELECT count(*) FROM events WHERE name = 'wa_offer_requested'")).longValue());
        assertEquals(0L, ((Number) one("SELECT count(*) FROM events WHERE name = 'wa_message_received'")).longValue(), "no greeting on top of the code");
        assertNotNull(one("SELECT used_at FROM wa_link_tokens WHERE ref = ?", ref));
        assertEquals(1L, ((Number) one("SELECT count(*) FROM push_prompt_events WHERE surface = 'wa_offer' AND step = 'token_minted'")).longValue());
        assertEquals("in", one("SELECT direction FROM wa_messages WHERE wamid = 'wamid.OFFER1'"), "the request shows in the inbox");
    }

    @Test
    void a_used_or_unknown_ref_still_gets_the_code_but_links_no_browser() throws SQLException {
        whatsapp("wamid.OFFER2", "Hi WUMIKA! Please send my 10% off code. (ref ZZZZZZ)");
        var customer = (UUID) one("SELECT identity_id FROM identity_keys WHERE kind = 'phone' AND value = ?", PHONE);
        assertNotEquals(customer, one("SELECT identity_id FROM identity_keys WHERE kind = 'anon' AND value = ?", anon));
        assertEquals("granted", one("""
                SELECT state::text FROM consent_current WHERE identity_id = ? AND channel = 'whatsapp' AND purpose = 'marketing'""", customer),
                "they wrote to us asking for it: their opt-in");
        assertEquals(1L, ((Number) one("SELECT count(*) FROM events WHERE name = 'wa_offer_requested'")).longValue());
        assertEquals(browser, one("SELECT identity_id FROM identity_keys WHERE kind = 'anon' AND value = ?", anon));
    }
}
