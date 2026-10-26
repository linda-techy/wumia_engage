package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Push released to everyone (PUSH_OPEN_TO_ALL) while the customer allowlist
 * (priya.k@example.com in tests) still holds back Shopify customer data:
 * every browser's push subscription is kept, and nothing else changes.
 */
@MicronautTest(transactional = false)
@Property(name = "engage.privacy.push-open-to-all", value = "true")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PushOpenToAllTest {

    static final String PUSH_COPY_V2 = "Get order updates, and an alert when your size is back or your bag price drops.";

    @Inject @Client("/") HttpClient client;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("TRUNCATE devices, push_prompt_events, stock_waitlist, consents, events, carts, identity_keys, profiles, identities CASCADE");
            st.execute("""
                INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
                VALUES ('push_v2', 'push', '%s', '{transactional,marketing}', 'soft_ask')
                ON CONFLICT (version) DO NOTHING""".formatted(PUSH_COPY_V2));
        }
    }

    @Test
    void an_anonymous_visitor_who_allows_push_is_kept_with_the_consent() throws SQLException {
        assertEquals(200, post(signedUri("/register", null), register(UUID.randomUUID().toString(), "tok-open-anon")));
        assertEquals(1, count("SELECT count(*) FROM devices"));
        assertEquals(2, count("SELECT count(*) FROM consents WHERE channel = 'push' AND state = 'granted'"), "order updates and offers");
        assertEquals(1, count("SELECT count(*) FROM events WHERE name = 'push_subscribed'"), "the welcome series starts");
    }

    @Test
    void a_signed_in_customer_outside_the_allowlist_is_kept_for_push_only() throws SQLException {
        assertEquals(200, post(signedUri("/register", "9999"), register(UUID.randomUUID().toString(), "tok-open-cust")));
        assertEquals(1, count("SELECT count(*) FROM devices"));
        assertEquals(0, count("SELECT count(*) FROM identity_keys WHERE kind IN ('email', 'phone')"),
                "no email or phone: WhatsApp and SMS stay behind the allowlist");
    }

    /* ------------------------------ helpers ------------------------------ */

    String signedUri(String path, String customerId) {
        var q = new TreeMap<String, String>();
        q.put("shop", System.getenv("SHOPIFY_SHOP_DOMAIN"));
        q.put("path_prefix", "/apps/push");
        q.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        q.put("logged_in_customer_id", customerId == null ? "" : customerId);
        var message = new StringBuilder();
        q.forEach((k, v) -> message.append(k).append('=').append(v));
        var signature = Hmacs.sha256Hex(System.getenv("SHOPIFY_API_SECRET"), message.toString().getBytes(StandardCharsets.UTF_8));
        var uri = new StringBuilder("/shopify/proxy").append(path).append('?');
        q.forEach((k, v) -> uri.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return uri.append("signature=").append(signature).toString();
    }

    int post(String uri, Map<String, Object> body) {
        try {
            return client.toBlocking().exchange(HttpRequest.POST(uri, body)).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    static Map<String, Object> register(String anonId, String token) {
        var b = new HashMap<String, Object>();
        b.put("anonId", anonId);
        b.put("token", token);
        b.put("surface", "browse");
        b.put("platform", "WEB");
        b.put("browser", "chrome");
        b.put("copyVersion", "push_v2");
        b.put("copyText", PUSH_COPY_V2);
        b.put("page", "/collections/diwali-gifts");
        return b;
    }

    long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
