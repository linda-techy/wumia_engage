package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxHousekeeping;
import in.brand.engage.ingest.inbox.InboxProcessor;
import in.brand.engage.ingest.metrics.MetricsAccessFilter;
import io.micronaut.context.annotation.Property;
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
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P1-T05: inbox housekeeping and the /prometheus metrics, against Postgres. */
@MicronautTest(transactional = false)
@Property(name = "engage.metrics.token", value = HousekeepingMetricsTest.TOKEN)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class HousekeepingMetricsTest {

    static final String TOKEN = "test-metrics-token-7d2e";

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject InboxHousekeeping housekeeping;
    @Inject DataSource dataSource;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("TRUNCATE webhook_inbox");
        }
    }

    @Test
    void prometheus_shows_all_five_and_a_poison_item_becomes_one_dead_letter() throws Exception {
        webhook("orders/create", Files.readString(Path.of("src/test/resources/fixtures/shopify_order_create.json")), true);
        webhook("orders/create", "{}", false);                                          // bad HMAC
        exec("""
            INSERT INTO webhook_inbox (source, delivery_id, topic, payload, attempts)
            VALUES ('poison', 'poison-1', 'poison/topic', '{}', 9)""");               // no handler: fails its 10th try
        processor.drain();
        housekeeping.refreshGauges();

        var text = scrape();
        assertEquals(1.0, value(text, "engage_inbox_dead_letters"), "the poison item is a dead letter");
        assertEquals(0.0, value(text, "engage_inbox_pending"));
        assertTrue(text.contains("engage_inbox_oldest_pending_seconds"), "oldest pending gauge");
        assertTrue(text.contains("engage_webhook_received_total{result=\"stored\",source=\"shopify\",topic=\"orders/create\"}"), text);
        assertTrue(text.contains("engage_webhook_received_total{result=\"unauthorized\",source=\"shopify\",topic=\"other\"}"),
                "a rejected request never names its own topic");
        assertTrue(text.contains("engage_handler_seconds_count{source=\"shopify\",topic=\"orders/create\"}"), "handler timer");
        assertTrue(text.contains("engage_handler_seconds_bucket"), "histogram buckets for the p99 alert");
        assertEquals("10", q("SELECT attempts FROM webhook_inbox WHERE delivery_id = 'poison-1'"));
    }

    @Test
    void pending_and_oldest_pending_come_from_the_inbox() throws Exception {
        exec("""
            INSERT INTO webhook_inbox (source, delivery_id, topic, payload, received_at, next_attempt_at)
            VALUES ('shopify', 'late-1', 'orders/create', '{}', now() - interval '10 minutes', now() + interval '1 hour'),
                   ('shopify', 'late-2', 'orders/create', '{}', now() - interval '2 minutes',  now() + interval '1 hour')""");
        housekeeping.refreshGauges();

        var text = scrape();
        assertEquals(2.0, value(text, "engage_inbox_pending"));
        double oldest = value(text, "engage_inbox_oldest_pending_seconds");
        assertTrue(oldest >= 600 && oldest < 660, "oldest is the 10-minute-old row: " + oldest);
    }

    @Test
    void the_purge_deletes_only_rows_processed_over_a_week_ago() throws Exception {
        exec("""
            INSERT INTO webhook_inbox (source, delivery_id, topic, payload, received_at, processed_at, attempts)
            VALUES ('shopify', 'old-done',    't/x', '{}', now() - interval '9 days',  now() - interval '8 days', 1),
                   ('shopify', 'recent-done', 't/x', '{}', now() - interval '6 days',  now() - interval '6 days', 1),
                   ('shopify', 'old-pending', 't/x', '{}', now() - interval '30 days', NULL, 3),
                   ('shopify', 'old-dead',    't/x', '{}', now() - interval '30 days', NULL, 10)""");

        assertEquals(1, housekeeping.purgeProcessed());

        assertEquals("old-dead,old-pending,recent-done",
                q("SELECT string_agg(delivery_id, ',' ORDER BY delivery_id) FROM webhook_inbox"));
    }

    @Test
    void prometheus_needs_the_bearer_token() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET("/prometheus")));
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET("/prometheus").bearerAuth("wrong")));
        assertEquals(HttpStatus.OK, status(HttpRequest.GET("/prometheus").bearerAuth(TOKEN)));
    }

    @Test
    void without_a_configured_token_prometheus_does_not_exist() {
        var res = new MetricsAccessFilter("").check(HttpRequest.GET("/prometheus").bearerAuth("anything"));
        assertEquals(HttpStatus.NOT_FOUND, res.status());
    }

    /* -------------------------------- helpers -------------------------------- */

    void webhook(String topic, String json, boolean signed) {
        var body = json.getBytes(StandardCharsets.UTF_8);
        var req = HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", signed ? Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body) : "bad");
        status(req);
    }

    String scrape() {
        return client.toBlocking().retrieve(HttpRequest.GET("/prometheus").bearerAuth(TOKEN));
    }

    static double value(String text, String name) {
        var m = Pattern.compile("(?m)^" + name + "(\\{[^}]*\\})? ([0-9.eE+-]+)$").matcher(text);
        assertTrue(m.find(), name + " missing");
        return Double.parseDouble(m.group(2));
    }

    HttpStatus status(HttpRequest<?> req) {
        try {
            return client.toBlocking().exchange(req).status();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
