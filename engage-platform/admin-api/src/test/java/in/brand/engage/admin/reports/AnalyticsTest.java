package in.brand.engage.admin.reports;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Analytics: the funnel, revenue credit (V41) and the campaign report. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class AnalyticsTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE sends, conversions, spend_ledger, campaign_recipients, campaigns CASCADE"));
    }

    UUID person() {
        var id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
        return id;
    }

    /** A send created {@code hoursAgo}, with the given timestamps in hours ago (null = never). */
    long send(UUID who, String channel, String intent, int hoursAgo, Integer deliveredAgo, Integer readAgo, Integer clickedAgo,
              int costPaise) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status,
                                       cost_paise, created_at, sent_at, delivered_at, read_at, clicked_at)
                    VALUES (?, CAST(? AS channel), 'marketing', 't', ?, ?, CAST(? AS send_status), ?,
                            now() - make_interval(hours => ?), now() - make_interval(hours => ?),
                            CASE WHEN CAST(? AS int) IS NULL THEN NULL ELSE now() - make_interval(hours => CAST(? AS int)) END,
                            CASE WHEN CAST(? AS int) IS NULL THEN NULL ELSE now() - make_interval(hours => CAST(? AS int)) END,
                            CASE WHEN CAST(? AS int) IS NULL THEN NULL ELSE now() - make_interval(hours => CAST(? AS int)) END)
                    RETURNING id""",
                    who, channel, intent, "k" + SEQ.incrementAndGet(),
                    clickedAgo != null ? "clicked" : readAgo != null ? "read" : deliveredAgo != null ? "delivered" : "sent",
                    costPaise, hoursAgo, hoursAgo, deliveredAgo, deliveredAgo, readAgo, readAgo, clickedAgo, clickedAgo);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    /** An order placed now, as ingest-api records it, then credited (V41). */
    String order(UUID who, long rupees) {
        var orderId = "o" + SEQ.incrementAndGet();
        return db.inTx(c -> {
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, ?, ?, ?, now())",
                    orderId, "#" + orderId, who, rupees * 100);
            Sql.update(c, "INSERT INTO conversions (identity_id, order_id, value_paise, occurred_at) VALUES (?, ?, ?, now())",
                    who, orderId, rupees * 100);
            try (var ps = Sql.prepare(c, "SELECT attribute_conversion(?)", orderId); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    String q(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    Map<String, Object> get(String path) {
        return client.toBlocking().retrieve(HttpRequest.GET(path).bearerAuth(data.loginAsViewer(client)), Map.class);
    }

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    static Map<String, Object> row(Map<String, Object> o, String list, String key) {
        return ((List<Map<String, Object>>) o.get(list)).stream().filter(r -> key.equals(r.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " in " + o.get(list)));
    }

    @Test void an_order_is_credited_to_the_latest_click_else_a_recent_read_and_never_to_an_order_update() {
        var clicker = person();
        send(clicker, "whatsapp", "winback", 72, 71, 70, null, 86);
        long push = send(clicker, "push", "cart_recovery", 50, 49, null, 48, 0);       // clicked 2 days ago
        assertEquals("click", order(clicker, 1500));
        assertEquals(String.valueOf(push), q("SELECT last_send_id::text FROM conversions WHERE identity_id = ?", clicker));

        var reader = person();
        long wa = send(reader, "whatsapp", "checkout_abandon", 4, 4, 3, null, 86);       // read 3 hours ago, no click
        assertEquals("view", order(reader, 2000));
        assertEquals(String.valueOf(wa), q("SELECT last_send_id::text FROM conversions WHERE identity_id = ?", reader));

        var informed = person();
        send(informed, "whatsapp", "order_tracking", 2, 2, 1, null, 12);                // "your order has shipped"
        assertNull(order(informed, 900), "an order update never takes the credit");

        var stale = person();
        send(stale, "push", "price_drop", 250, 249, null, 240, 0);                      // clicked 10 days ago
        assertNull(order(stale, 900), "outside the 7-day click window");
    }

    @Test void the_overview_counts_the_funnel_and_the_credited_revenue_by_channel_and_journey() {
        var a = person();
        send(a, "push", "cart_recovery", 30, 29, null, 28, 0);
        order(a, 1500);
        var b = person();
        send(b, "push", "cart_recovery", 30, 29, null, null, 0);
        var c2 = person();
        send(c2, "whatsapp", "campaign:" + UUID.randomUUID(), 5, 5, 4, null, 86);
        order(c2, 2000);

        var o = get("/api/analytics/overview");
        var push = row(o, "channels", "push");
        assertEquals(2, n(push, "sent"));
        assertEquals(2, n(push, "delivered"));
        assertEquals(1, n(push, "clicked"));
        assertEquals(1, n(push, "orders"));
        assertEquals(150000, n(push, "revenuePaise"));
        assertEquals(1, n(push, "clickOrders"));
        var wa = row(o, "channels", "whatsapp");
        assertEquals(1, n(wa, "read"));
        assertEquals(86, n(wa, "costPaise"));
        assertEquals(1, n(wa, "viewOrders"));
        assertEquals(350000, n((Map<String, Object>) o.get("total"), "revenuePaise"));
        assertEquals(1, n(row(o, "journeys", "campaigns"), "orders"), "every campaign is grouped as campaigns");
        assertEquals(2, n(row(o, "journeys", "cart_recovery"), "sent"));
        assertFalse(((List<?>) o.get("daily")).isEmpty());
        assertEquals(7, n(o, "clickDays"));
    }

    @Test void a_campaign_report_shows_its_funnel_and_messaged_against_held_out() {
        var owner = data.createOperator("lead@example.com", "hunter2hunter2", "CAMPAIGN_EDIT");
        var campaign = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO templates (key, channel, category, status) VALUES ('push_t', 'push', 'marketing', 'active') ON CONFLICT DO NOTHING");
            return Sql.update(c, """
                    INSERT INTO campaigns (id, name, channel, template_key, status, created_by, started_at)
                    VALUES (?, 'Onam drop', 'push', 'push_t', 'COMPLETED', ?, now() - interval '1 day')""", campaign, owner);
        });
        for (int i = 0; i < 4; i++) {
            var p = person();
            db.inTx(c -> Sql.update(c, "INSERT INTO campaign_recipients (campaign_id, identity_id, bucket, state) VALUES (?, ?, 'treatment', 'sent')", campaign, p));
            send(p, "push", "campaign:" + campaign, 20, 20, null, i < 2 ? 19 : null, 0);
            if (i == 0) order(p, 3000);
        }
        var held = person();
        db.inTx(c -> Sql.update(c, "INSERT INTO campaign_recipients (campaign_id, identity_id, bucket, state) VALUES (?, ?, 'control', 'skipped')", campaign, held));

        var r = get("/api/campaigns/" + campaign + "/report");
        var total = (Map<String, Object>) r.get("total");
        assertEquals(4, n(total, "sent"));
        assertEquals(2, n(total, "clicked"));
        assertEquals(1, n(total, "orders"));
        assertEquals(300000, n(total, "revenuePaise"));
        var h = (Map<String, Object>) r.get("holdout");
        assertEquals(4, n(h, "treated"));
        assertEquals(1, n(h, "control"));
        assertEquals(1, n(h, "treatedBuyers"));
        assertEquals(false, h.get("enoughData"), "30 in each group before it means anything");

        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/analytics/campaigns").bearerAuth(data.loginAsViewer(client)),
                io.micronaut.core.type.Argument.listOf(Map.class));
        assertEquals("Onam drop", list.getFirst().get("name"));
        assertEquals(4, n((Map<String, Object>) list.getFirst(), "recipients"));
    }

    @Test void a_range_with_no_sends_still_answers_every_list_empty_not_missing() {
        // Production on day one: no sends yet. The console reads o.channels.find(...).
        var o = get("/api/analytics/overview?from=2025-01-01&to=2025-01-31");
        assertEquals(java.util.List.of(), o.get("channels"));
        assertEquals(java.util.List.of(), o.get("journeys"));
        assertNotNull(o.get("daily"), "daily is a list (zero days), present either way");
    }

    @Test void ranges_are_checked_and_spend_blocks_and_quality_answer() {
        var token = data.loginAsViewer(client);
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/analytics/overview?from=2026-01-01&to=2026-06-01").bearerAuth(token), Map.class));
        assertEquals(400, e.getStatus().getCode());
        var bad = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/reports/spend?from=yesterday").bearerAuth(token), io.micronaut.core.type.Argument.listOf(Map.class)));
        assertEquals(400, bad.getStatus().getCode());

        db.inTx(c -> Sql.update(c, """
                INSERT INTO spend_ledger (day, channel, category, messages, paise)
                VALUES ((now() AT TIME ZONE 'Asia/Kolkata')::date, 'whatsapp', 'marketing', 10, 860)"""));
        var spend = client.toBlocking().retrieve(HttpRequest.GET("/api/reports/spend").bearerAuth(token), io.micronaut.core.type.Argument.listOf(Map.class));
        assertEquals(1, spend.size());
        var p = person();
        db.inTx(c -> Sql.update(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, decision)
                VALUES (?, 'push', 'marketing', 't', 'blk1', 'blocked', '{"reason":"QUIET_HOURS"}')""", p));
        var blocks = client.toBlocking().retrieve(HttpRequest.GET("/api/reports/blocks").bearerAuth(token), io.micronaut.core.type.Argument.listOf(Map.class));
        assertEquals("QUIET_HOURS", blocks.getFirst().get("reason"));
        assertNotNull(client.toBlocking().retrieve(HttpRequest.GET("/api/reports/quality").bearerAuth(token), Map.class).get("templates"));
    }
}
