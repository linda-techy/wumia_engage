package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
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
 * Storefront subscriber endpoints behind the App Proxy (phase-2 §8, §9):
 * signed request → identity → device → consent ledger, against Postgres.
 *
 * <p>The test allowlist is priya.k@example.com (see ingest-api/build.gradle.kts).
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class StorefrontSubscriberTest {

    static final String PUSH_COPY = "Get an alert when your size is back or your bag price drops.";
    static final String ALLOWLISTED_CUSTOMER = "7001";

    @Inject @Client("/") HttpClient client;
    @Inject DataSource dataSource;

    UUID allowlistedIdentity;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean non-test database " + rs.getString(1));
            st.execute("""
                TRUNCATE devices, push_prompt_events, stock_waitlist, consents, events, carts,
                         identity_keys, profiles, identities CASCADE""");
            // An allowlisted customer as the customers/update webhook would leave them.
            var id = st.executeQuery("""
                SELECT resolve_identity('[{"kind":"shopify_customer","value":"7001","verified":true},
                                          {"kind":"email","value":"priya.k@example.com","trust":"buyer"}]'::jsonb)""");
            id.next();
            allowlistedIdentity = id.getObject(1, UUID.class);
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    String signedUri(String path, String customerId, long timestamp) {
        var q = new TreeMap<String, String>();
        q.put("shop", System.getenv("SHOPIFY_SHOP_DOMAIN"));
        q.put("path_prefix", "/apps/push");
        q.put("timestamp", String.valueOf(timestamp));
        q.put("logged_in_customer_id", customerId == null ? "" : customerId);
        var message = new StringBuilder();
        q.forEach((k, v) -> message.append(k).append('=').append(v));
        var signature = Hmacs.sha256Hex(System.getenv("SHOPIFY_API_SECRET"),
                message.toString().getBytes(StandardCharsets.UTF_8));
        var uri = new StringBuilder("/shopify/proxy").append(path).append('?');
        q.forEach((k, v) -> uri.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return uri.append("signature=").append(signature).toString();
    }

    String signedUri(String path, String customerId) {
        return signedUri(path, customerId, Instant.now().getEpochSecond());
    }

    int post(String uri, Map<String, Object> body) {
        try {
            return client.toBlocking().exchange(HttpRequest.POST(uri, body)).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    Map<String, Object> register(String anonId, String token) {
        var b = new HashMap<String, Object>();
        b.put("anonId", anonId);
        b.put("token", token);
        b.put("surface", "add_to_cart");
        b.put("platform", "WEB");
        b.put("browser", "chrome");
        b.put("copyVersion", "push_v1");
        b.put("copyText", PUSH_COPY);
        b.put("page", "/products/linen-shirt");
        return b;
    }

    Object one(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    long count(String sql, Object... params) throws SQLException {
        return ((Number) one(sql, params)).longValue();
    }

    static String anon() {
        return UUID.randomUUID().toString();
    }

    /* ------------------------------ register ----------------------------- */

    @Test
    void register_binds_the_device_and_a_push_grant_to_the_signed_customer() throws SQLException {
        assertEquals(200, post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anon(), "tok-A")));

        assertEquals(allowlistedIdentity, one("SELECT identity_id FROM devices WHERE fcm_token = 'tok-A'"));
        assertEquals(true, one("SELECT active FROM devices WHERE fcm_token = 'tok-A'"));
        assertEquals("add_to_cart", one("SELECT permission_source FROM devices WHERE fcm_token = 'tok-A'"));

        assertEquals("granted", one("""
            SELECT state::text FROM consent_current
             WHERE identity_id = ? AND channel = 'push' AND purpose = 'marketing'""", allowlistedIdentity));
        assertEquals(PUSH_COPY, one("""
            SELECT evidence->>'copy_text' FROM consents WHERE identity_id = ? AND channel = 'push'""",
            allowlistedIdentity));
        assertEquals("soft_ask:add_to_cart", one("SELECT source FROM consents WHERE identity_id = ?", allowlistedIdentity));
    }

    @Test
    void a_browser_that_belonged_to_someone_else_is_bound_to_the_signed_customer() throws SQLException {
        // The anon id first seen on another (anonymous) identity. The request body
        // cannot choose the customer: only the signed logged_in_customer_id does.
        var anonId = anon();
        UUID other;
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("SELECT resolve_identity('[{\"kind\":\"anon\",\"value\":\"" + anonId + "\"}]'::jsonb)");
            rs.next();
            other = rs.getObject(1, UUID.class);
        }
        assertNotEquals(allowlistedIdentity, other);

        assertEquals(200, post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anonId, "tok-B")));
        assertEquals(allowlistedIdentity, one("SELECT identity_id FROM devices WHERE fcm_token = 'tok-B'"));
    }

    @Test
    void anonymous_visitors_are_not_stored_while_the_allowlist_is_restricted() throws SQLException {
        assertEquals(202, post(signedUri("/register", null), register(anon(), "tok-anon")));
        assertEquals(0, count("SELECT count(*) FROM devices"));
        assertEquals(0, count("SELECT count(*) FROM consents WHERE channel = 'push'"));
    }

    @Test
    void customers_outside_the_allowlist_are_not_stored() throws SQLException {
        assertEquals(202, post(signedUri("/register", "9999"), register(anon(), "tok-other")));
        assertEquals(0, count("SELECT count(*) FROM devices"));
        assertEquals(0, count("SELECT count(*) FROM identity_keys WHERE kind = 'shopify_customer' AND value = '9999'"));
    }

    @Test
    void unsigned_or_stale_requests_are_refused() throws SQLException {
        assertEquals(401, post("/shopify/proxy/register?shop=x&timestamp=1", register(anon(), "tok-x")));
        var stale = Instant.now().minusSeconds(3600).getEpochSecond();
        assertEquals(401, post(signedUri("/register", ALLOWLISTED_CUSTOMER, stale), register(anon(), "tok-x")));
        assertEquals(0, count("SELECT count(*) FROM devices"));
    }

    @Test
    void an_unregistered_copy_version_or_edited_wording_grants_nothing() throws SQLException {
        var unknown = register(anon(), "tok-v9");
        unknown.put("copyVersion", "push_v9");
        assertEquals(422, post(signedUri("/register", ALLOWLISTED_CUSTOMER), unknown));

        var edited = register(anon(), "tok-edit");
        edited.put("copyText", "Edited in the theme editor without a version bump");
        assertEquals(422, post(signedUri("/register", ALLOWLISTED_CUSTOMER), edited));

        assertEquals(0, count("SELECT count(*) FROM devices"));
        assertEquals(0, count("SELECT count(*) FROM consents"));
    }

    @Test
    void registering_again_adds_no_device_and_no_consent_row() throws SQLException {
        var anonId = anon();
        assertEquals(200, post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anonId, "tok-R")));
        assertEquals(200, post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anonId, "tok-R")));
        assertEquals(1, count("SELECT count(*) FROM devices"));
        assertEquals(1, count("SELECT count(*) FROM consents WHERE channel = 'push'"));
    }

    @Test
    void invalid_bodies_are_rejected() {
        var noToken = register(anon(), "");
        assertEquals(400, post(signedUri("/register", ALLOWLISTED_CUSTOMER), noToken));
        var badAnon = register("not-a-uuid", "tok-q");
        assertEquals(400, post(signedUri("/register", ALLOWLISTED_CUSTOMER), badAnon));
        var badSurface = register(anon(), "tok-q");
        badSurface.put("surface", "landing_page");
        assertEquals(400, post(signedUri("/register", ALLOWLISTED_CUSTOMER), badSurface));
    }

    /* ------------------------ refresh / unregister ----------------------- */

    @Test
    void refresh_with_a_new_token_rotates_the_old_one_out() throws SQLException {
        var anonId = anon();
        post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anonId, "tok-old"));

        assertEquals(200, post(signedUri("/refresh", ALLOWLISTED_CUSTOMER),
                Map.of("anonId", anonId, "token", "tok-new", "previous", "tok-old")));

        assertEquals(false, one("SELECT active FROM devices WHERE fcm_token = 'tok-old'"));
        assertEquals("rotated", one("SELECT deactivated_reason FROM devices WHERE fcm_token = 'tok-old'"));
        assertEquals(true, one("SELECT active FROM devices WHERE fcm_token = 'tok-new'"));
        assertEquals("push_v1", one("SELECT consent_copy_ver FROM devices WHERE fcm_token = 'tok-new'"));
        // Rotation is not a new consent.
        assertEquals(1, count("SELECT count(*) FROM consents WHERE channel = 'push'"));
    }

    @Test
    void unregister_deactivates_the_device_and_withdraws_push_consent() throws SQLException {
        var anonId = anon();
        post(signedUri("/register", ALLOWLISTED_CUSTOMER), register(anonId, "tok-off"));

        assertEquals(200, post(signedUri("/unregister", ALLOWLISTED_CUSTOMER), Map.of("anonId", anonId, "token", "tok-off")));

        assertEquals("user_off", one("SELECT deactivated_reason FROM devices WHERE fcm_token = 'tok-off'"));
        assertEquals("withdrawn", one("""
            SELECT state::text FROM consent_current
             WHERE identity_id = ? AND channel = 'push' AND purpose = 'marketing'""", allowlistedIdentity));
    }

    /* ------------------------ funnel and waitlist ------------------------ */

    @Test
    void prompt_events_are_recorded_for_anonymous_visitors_without_an_identity() throws SQLException {
        assertEquals(204, post(signedUri("/prompt-event", null),
                Map.of("anonId", anon(), "surface", "add_to_cart", "step", "soft_shown", "platform", "WEB")));
        assertEquals(1, count("SELECT count(*) FROM push_prompt_events WHERE step = 'soft_shown' AND identity_id IS NULL"));

        assertEquals(400, post(signedUri("/prompt-event", null),
                Map.of("anonId", anon(), "surface", "add_to_cart", "step", "made_up_step")));
    }

    @Test
    void notify_me_adds_a_size_waitlist_row() throws SQLException {
        assertEquals(200, post(signedUri("/notify-me", ALLOWLISTED_CUSTOMER),
                Map.of("anonId", anon(), "variantId", "4455", "productHandle", "linen-shirt", "sizeLabel", "M")));
        assertEquals("M", one("SELECT size_label FROM stock_waitlist WHERE identity_id = ? AND variant_id = '4455'",
                allowlistedIdentity));
    }
}
