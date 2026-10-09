package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T01: the Meta webhook handshake, signature, dedupe, and the customer allowlist by phone. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class MetaWebhookTest {

    static final Path FX = Path.of("src/test/resources/fixtures");
    static final String SECRET = "test-meta-app-secret";       // build.gradle.kts test env

    @Inject @Client("/") HttpClient client;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        exec("TRUNCATE webhook_inbox, identity_keys, profiles, identities CASCADE");
    }

    int post(byte[] body, String signature) {
        var req = HttpRequest.POST("/meta/webhook", body).contentType(MediaType.APPLICATION_JSON);
        if (signature != null) req = req.header("X-Hub-Signature-256", signature);
        try {
            return client.toBlocking().exchange(req).code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    static String signed(byte[] body) {
        return "sha256=" + Hmacs.sha256Hex(SECRET, body);
    }

    static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(FX.resolve(name));
    }

    /** Priya, allowlisted by email, with the phone the fixtures use. */
    void allowlistedCustomerWithPhone() throws SQLException {
        var id = UUID.randomUUID();
        exec("INSERT INTO identities (id) VALUES ('" + id + "')");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + id + "', 'email', 'priya.k@example.com', true)");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + id + "', 'phone', '919876543210', true)");
    }

    @Test
    void the_subscription_handshake_echoes_the_challenge_only_for_our_token() {
        var ok = client.toBlocking().retrieve(HttpRequest.GET(
                "/meta/webhook?hub.mode=subscribe&hub.verify_token=test-verify-token&hub.challenge=1158201444"), String.class);
        assertEquals("1158201444", ok);
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(HttpRequest.GET(
                "/meta/webhook?hub.mode=subscribe&hub.verify_token=guess&hub.challenge=1"), String.class));
        assertEquals(403, e.getStatus().getCode());
    }

    @Test
    void a_signed_webhook_is_stored_once_however_often_meta_retries() throws Exception {
        allowlistedCustomerWithPhone();
        var body = fixture("meta_status_delivered.json");
        assertEquals(200, post(body, signed(body)));
        assertEquals(200, post(body, signed(body)));
        assertEquals("1|messages", q("SELECT count(*) || '|' || min(topic) FROM webhook_inbox WHERE source = 'meta'"));
    }

    @Test
    void a_bad_or_unprefixed_signature_is_401_and_nothing_is_stored() throws Exception {
        allowlistedCustomerWithPhone();
        var body = fixture("meta_inbound_text.json");
        assertEquals(401, post(body, "sha256=" + Hmacs.sha256Hex("the-access-token", body)));
        assertEquals(401, post(body, Hmacs.sha256Hex(SECRET, body)), "sha256= prefix required");
        assertEquals(401, post(body, null));
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox"));
    }

    @Test
    void a_number_that_is_not_an_allowlisted_customer_is_acknowledged_and_dropped() throws Exception {
        var body = fixture("meta_inbound_stop_hinglish.json");   // 919876543210 belongs to nobody here
        assertEquals(200, post(body, signed(body)));
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox"));

        allowlistedCustomerWithPhone();
        assertEquals(200, post(body, signed(body)));
        assertEquals("1", q("SELECT count(*) FROM webhook_inbox"), "the same message, once the number is a test customer's");
    }

    @Test
    void a_payload_without_customer_numbers_is_stored() throws Exception {
        var body = fixture("meta_template_status.json");
        assertEquals(200, post(body, signed(body)));
        assertEquals("message_template_status_update", q("SELECT topic FROM webhook_inbox WHERE source = 'meta'"));
    }

    void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            st.execute(sql);
        }
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
