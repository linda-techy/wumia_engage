package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.aws.SnsSignatureVerifier;
import in.brand.engage.core.crypto.UnsubscribeToken;
import in.brand.engage.ingest.inbox.InboxProcessor;
import in.brand.engage.ingest.web.SnsCertificates;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T01: one-click unsubscribe, and SES bounces and complaints through signed SNS. */
@MicronautTest(transactional = false)
@Property(name = "engage.ses.topic-arn", value = EmailFeedbackTest.TOPIC)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class EmailFeedbackTest {

    static final String TOPIC = "arn:aws:sns:ap-south-1:123456789012:engage-ses";
    static final String CERT_URL = "https://sns.ap-south-1.amazonaws.com/SimpleNotificationService-test.pem";
    static final TestCerts.Generated KEYS;

    static {
        try {
            KEYS = TestCerts.generate("CN=sns.amazonaws.com");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Stands in for fetching AWS's certificate. */
    @Singleton
    @Replaces(SnsCertificates.class)
    static class TestSnsCertificates extends SnsCertificates {
        @Override public X509Certificate get(URI url) {
            return url.toString().equals(CERT_URL) ? KEYS.cert() : null;
        }
    }

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;
    @Inject UnsubscribeToken tokens;

    String identity;

    @BeforeEach void setUp() throws SQLException {
        exec("TRUNCATE webhook_inbox, events, consents, suppressions, identity_keys, profiles, identities CASCADE");
        identity = UUID.randomUUID().toString();
        exec("INSERT INTO identities (id) VALUES ('" + identity + "')");
        exec("INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES ('" + identity + "', 'email', 'priya.k@example.com', true)");
        exec("INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at) VALUES ('" + identity
                + "', 'email', 'marketing', 'granted', 'test', now() - interval '30 days')");
    }

    /* ------------------------------ unsubscribe ------------------------------ */

    @Test void opening_the_link_does_not_unsubscribe_but_the_one_click_post_does_once() throws Exception {
        var token = tokens.of(UUID.fromString(identity));
        var page = client.toBlocking().exchange(HttpRequest.GET("/u/" + token), String.class);
        assertTrue(page.body().contains("<form method=\"post\">"));
        assertEquals("granted", emailMarketing(), "a mail scanner opening the link changes nothing");

        for (int i = 0; i < 2; i++) {
            var res = client.toBlocking().exchange(HttpRequest.POST("/u/" + token, "List-Unsubscribe=One-Click")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE), String.class);
            assertEquals(200, res.code());
        }
        assertEquals("withdrawn", emailMarketing());
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'email_unsubscribed'"), "a second click is a no-op");
    }

    @Test void a_forged_token_is_not_found() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.POST("/u/" + "A".repeat(44), "").contentType(MediaType.TEXT_PLAIN_TYPE)));
        assertEquals(404, e.getStatus().getCode());
    }

    /* ------------------------------ SES via SNS ------------------------------ */

    @Test void a_hard_bounce_suppresses_email_for_good() throws Exception {
        assertEquals(200, sns("Notification", bounce("Permanent")));
        assertEquals("hard_bounce|true", q("SELECT reason || '|' || (until IS NULL) FROM suppressions WHERE identity_id = '" + identity
                + "' AND channel = 'email'"));
    }

    @Test void three_soft_bounces_in_a_week_suppress_for_30_days() throws Exception {
        sns("Notification", bounce("Transient"));
        sns("Notification", bounce("Transient"));
        assertEquals("0", q("SELECT count(*) FROM suppressions WHERE identity_id = '" + identity + "'"));
        sns("Notification", bounce("Transient"));
        assertEquals("soft_bounces|true", q("SELECT reason || '|' || (until > now() + interval '29 days') FROM suppressions "
                + "WHERE identity_id = '" + identity + "'"));
    }

    @Test void a_complaint_suppresses_and_withdraws_email_marketing() throws Exception {
        sns("Notification", "{\"eventType\":\"Complaint\",\"complaint\":{\"complainedRecipients\":[{\"emailAddress\":"
                + "\"priya.k@example.com\"}],\"complaintFeedbackType\":\"abuse\"},\"mail\":{\"messageId\":\"m-c1\"}}");
        assertEquals("complaint", q("SELECT reason FROM suppressions WHERE identity_id = '" + identity + "'"));
        assertEquals("withdrawn", emailMarketing());
    }

    @Test void a_forged_or_foreign_message_is_refused() throws Exception {
        var m = signed("Notification", bounce("Permanent"));
        m.put("Message", m.get("Message").replace("Permanent", "Transient"));
        assertEquals(401, post(m));
        var other = signed("Notification", bounce("Permanent"));
        other.put("TopicArn", "arn:aws:sns:ap-south-1:999:someone-else");
        assertEquals(401, post(other));
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox WHERE source = 'ses'"));
    }

    /* -------------------------------- fixtures -------------------------------- */

    static String bounce(String type) {
        return "{\"eventType\":\"Bounce\",\"bounce\":{\"bounceType\":\"" + type + "\",\"bouncedRecipients\":[{\"emailAddress\":"
                + "\"Priya.K@example.com\"}]},\"mail\":{\"messageId\":\"m-" + UUID.randomUUID() + "\"}}";
    }

    int sns(String type, String message) throws Exception {
        int code = post(signed(type, message));
        processor.drain();
        return code;
    }

    LinkedHashMap<String, String> signed(String type, String message) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("Type", type);
        m.put("MessageId", UUID.randomUUID().toString());
        m.put("TopicArn", TOPIC);
        m.put("Message", message);
        m.put("Timestamp", "2026-10-15T06:00:00.000Z");
        m.put("SignatureVersion", "2");
        m.put("SigningCertURL", CERT_URL);
        var s = Signature.getInstance("SHA256withRSA");
        s.initSign(KEYS.keys().getPrivate());
        s.update(SnsSignatureVerifier.stringToSign(m).getBytes(StandardCharsets.UTF_8));
        m.put("Signature", Base64.getEncoder().encodeToString(s.sign()));
        return m;
    }

    int post(java.util.Map<String, String> m) throws Exception {
        var body = ObjectMapper.getDefault().writeValueAsBytes(m);
        try {
            HttpResponse<?> res = client.toBlocking().exchange(HttpRequest.POST("/webhooks/ses", body).contentType(MediaType.TEXT_PLAIN_TYPE));
            return res.code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    String emailMarketing() throws SQLException {
        return q("SELECT state FROM consent_current WHERE identity_id = '" + identity + "' AND channel = 'email' AND purpose = 'marketing'");
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
