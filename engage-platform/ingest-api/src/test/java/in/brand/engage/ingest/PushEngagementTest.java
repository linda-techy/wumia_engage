package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.BeaconSignature;
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
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Service-worker beacons joined to their send (P3-T08), through the signed App Proxy, against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PushEngagementTest {

    @Inject @Client("/") HttpClient client;
    @Inject DataSource dataSource;

    final BeaconSignature beacons = BeaconSignature.fromAppSecret(System.getenv("SHOPIFY_API_SECRET"));
    long sendId;
    long runId;

    @BeforeEach
    void aSentPush() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            var identity = UUID.randomUUID();
            exec(c, "INSERT INTO identities (id) VALUES (?)", identity);
            runId = id(c, """
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status, outcome)
                    VALUES ('cart_recovery', ?, ?, 3, 'exhausted', 'sent') RETURNING id""", identity, "cart-" + identity);
            sendId = id(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key,
                                       status, cascade_run_id, sent_at)
                    VALUES (?, 'push', 'marketing', 'push_cart_recovery_v1', 'cart_recovery', ?, 'sent', ?, now())
                    RETURNING id""", identity, "test:" + UUID.randomUUID(), runId);
        }
    }

    @Test void a_signed_click_marks_the_send_clicked_and_emits_one_push_clicked_event() throws SQLException {
        assertEquals(204, beacon("click", String.valueOf(sendId), beacons.sign(sendId)));
        assertEquals(204, beacon("click", String.valueOf(sendId), beacons.sign(sendId)));   // a second click

        assertEquals("clicked", one("SELECT status::text FROM sends WHERE id = ?", sendId));
        assertNotNull(one("SELECT clicked_at FROM sends WHERE id = ?", sendId));
        assertEquals(1L, count("SELECT count(*) FROM events WHERE name = 'push_clicked' AND props->>'send_id' = ?",
                String.valueOf(sendId)), "the first click wins; the second writes nothing");
        assertEquals(String.valueOf(runId), one("""
                SELECT props->>'cascade_run_id' FROM events WHERE name = 'push_clicked' AND props->>'send_id' = ?""",
                String.valueOf(sendId)));
    }

    @Test void a_forged_or_missing_signature_records_nothing() throws SQLException {
        assertEquals(204, beacon("click", String.valueOf(sendId), beacons.sign(sendId + 1)));
        assertEquals(204, beacon("click", String.valueOf(sendId), null));

        assertNull(one("SELECT clicked_at FROM sends WHERE id = ?", sendId));
        assertEquals(0L, count("SELECT count(*) FROM events WHERE name = 'push_clicked' AND props->>'send_id' = ?",
                String.valueOf(sendId)));
    }

    @Test void a_signed_impression_marks_the_push_delivered() throws SQLException {
        assertEquals(204, beacon("impression", String.valueOf(sendId), beacons.sign(sendId)));

        assertEquals("delivered", one("SELECT status::text FROM sends WHERE id = ?", sendId));
        assertNotNull(one("SELECT delivered_at FROM sends WHERE id = ?", sendId));
    }

    @Test void a_beacon_without_a_send_id_is_acknowledged_and_ignored() {
        assertEquals(204, beacon("click", null, null));
    }

    /* -------------------------------- helpers -------------------------------- */

    int beacon(String kind, String sid, String sig) {
        var body = new HashMap<String, Object>();
        body.put("kind", kind);
        if (sid != null) body.put("sid", sid);
        if (sig != null) body.put("sig", sig);
        body.put("sw", "test");
        try {
            return client.toBlocking().exchange(HttpRequest.POST(signedUri("/engagement"), body)).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    /** An App Proxy request as Shopify signs it; beacons come from anonymous visitors too. */
    static String signedUri(String path) {
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

    static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    static long id(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
