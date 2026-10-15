package in.brand.engage.admin.reports;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T03: lift against the holdout, and our WhatsApp ledger against Meta's bill. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class ReportsTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE holdouts, sends, spend_ledger, meta_billing, spend_reconciliations CASCADE"));
    }

    /**
     * {@code n} people in a bucket, entered {@code daysAgo} days ago; the first
     * {@code buyers} of them each placed one ₹{@code rupees} order since.
     */
    void bucket(String experiment, String bucket, int n, int daysAgo, int buyers, long rupees) {
        db.inTx(c -> {
            for (int i = 0; i < n; i++) {
                var id = UUID.randomUUID();
                Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
                Sql.update(c, "INSERT INTO holdouts (identity_id, experiment, bucket, assigned_at) VALUES (?, ?, ?, now() - make_interval(days => ?))",
                        id, experiment, bucket, daysAgo);
                if (i < buyers) {
                    Sql.update(c, """
                            INSERT INTO orders (id, order_number, identity_id, total_paise, created_at)
                            VALUES (?, ?, ?, ?, now() - interval '3 days')""",
                            "o-" + id, "#" + i, id, rupees * 100);
                }
            }
            return null;
        });
    }

    void sendCost(String intent, long paise) {
        var id = UUID.fromString(data.createIdentity(UUID.randomUUID() + "@example.com", "9198" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000))));
        db.inTx(c -> Sql.update(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status, cost_paise)
                VALUES (?, 'whatsapp', 'marketing', 't', ?, ?, 'delivered', ?)""", id, intent, UUID.randomUUID().toString(), paise));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    List<Map<String, Object>> lift(String token) {
        return (List) client.toBlocking().retrieve(HttpRequest.GET("/api/reports/lift?days=56").bearerAuth(token), io.micronaut.core.type.Argument.listOf(Map.class));
    }

    static double num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    @Test void a_journey_that_earns_more_than_its_holdout_shows_its_lift_cost_and_roi() {
        // treated: 400 people, 25% bought ₹2,000; holdout: 60 people, 10% bought ₹2,000
        bucket("checkout_abandon", "treatment", 400, 40, 100, 2000);
        bucket("checkout_abandon", "control", 60, 40, 6, 2000);
        sendCost("checkout_abandon", 40_000);
        sendCost("checkout_abandon", 35_000);
        sendCost("cart_recovery", 99_999);

        var row = lift(data.loginAsAnalyst(client)).stream()
                .filter(r -> r.get("journey").equals("checkout_abandon")).findFirst().orElseThrow();
        assertEquals(true, row.get("enoughData"), String.valueOf(row.get("why")));
        assertEquals(400, num(row, "treated"));
        assertEquals(60, num(row, "control"));
        assertEquals(50_000, num(row, "revenuePerTreatedPaise"), 1e-6);   // ₹500 a head
        assertEquals(20_000, num(row, "revenuePerControlPaise"), 1e-6);   // ₹200 a head
        assertEquals(150.0, num(row, "liftPct"), 1e-6);
        assertEquals(400L * 30_000, ((Number) row.get("incrementalPaise")).longValue());
        assertEquals(75_000, ((Number) row.get("messagingCostPaise")).longValue(), "only this journey's sends");
        assertEquals(400.0 * 30_000 / 75_000, num(row, "roi"), 1e-9);
        assertEquals(true, row.get("significant"));
        assertTrue(num(row, "incrementalLowPaise") > 0 && num(row, "incrementalHighPaise") > num(row, "incrementalPaise"));
        assertFalse(((List<?>) row.get("weekly")).isEmpty());
    }

    @Test void under_four_weeks_or_thirty_people_is_not_enough_data() {
        bucket("winback", "treatment", 400, 10, 100, 2000);
        bucket("winback", "control", 60, 10, 6, 2000);
        bucket("price_drop", "treatment", 100, 40, 10, 1000);
        bucket("price_drop", "control", 10, 40, 1, 1000);

        var rows = lift(data.loginAsAnalyst(client));
        var winback = rows.stream().filter(r -> r.get("journey").equals("winback")).findFirst().orElseThrow();
        assertEquals(false, winback.get("enoughData"));
        assertEquals("under 4 weeks of holdout", winback.get("why"));
        assertEquals(false, winback.get("significant"), "never called significant before there is enough data");
        var priceDrop = rows.stream().filter(r -> r.get("journey").equals("price_drop")).findFirst().orElseThrow();
        assertEquals(false, priceDrop.get("enoughData"));
        assertEquals("fewer than 30 people in a group", priceDrop.get("why"));
    }

    @Test void one_journey_by_its_key_and_404_without_a_holdout() {
        bucket("browse_abandon", "treatment", 40, 30, 4, 1500);
        bucket("browse_abandon", "control", 40, 30, 2, 1500);
        var token = data.loginAsAnalyst(client);
        var one = client.toBlocking().retrieve(
                HttpRequest.GET("/api/journeys/browse_abandon/incrementality").bearerAuth(token), Map.class);
        assertEquals("browse_abandon", one.get("journey"));
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/journeys/nope/incrementality").bearerAuth(token), Map.class));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test void the_lift_report_is_for_analysts() {
        var e = assertThrows(HttpClientResponseException.class, () -> lift(data.loginAsViewer(client)));
        assertEquals(403, e.getStatus().getCode());
    }

    @Test void the_meta_bill_is_compared_with_our_ledger_and_more_than_one_percent_is_flagged() {
        var month = YearMonth.now().minusMonths(1);
        var day = month.atDay(10);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO spend_ledger (day, channel, category, messages, paise) VALUES
                  (?, 'whatsapp', 'marketing', 1000, 860000),
                  (?, 'whatsapp', 'utility', 2000, 230000),
                  (?, 'push', 'marketing', 9999, 0)""", day, day, day));

        var upload = HttpRequest.POST("/api/reports/meta-billing",
                        "month,category,amount_inr\n" + month + ",marketing,8650.00\n" + month + ",utility,2400\n")
                .contentType(MediaType.of("text/csv")).bearerAuth(data.loginAsConfigAdmin(client));
        var done = client.toBlocking().retrieve(upload, Map.class);
        assertEquals(2, ((Number) done.get("rows")).intValue());
        assertEquals(1, data.countAudit("meta_billing.upload"));

        var rec = client.toBlocking().retrieve(HttpRequest.GET("/api/reports/spend-reconciliation?month=" + month)
                .bearerAuth(data.loginAsViewer(client)), Map.class);
        assertEquals(true, rec.get("billUploaded"));
        var rows = (List<Map<String, Object>>) rec.get("rows");
        var marketing = rows.stream().filter(r -> r.get("category").equals("marketing")).findFirst().orElseThrow();
        assertEquals(865_000, ((Number) marketing.get("metaPaise")).longValue());
        assertEquals(false, marketing.get("flagged"), "0.58% off");
        var utility = rows.stream().filter(r -> r.get("category").equals("utility")).findFirst().orElseThrow();
        assertEquals(-4.17, num(utility, "diffPct"), 0.01);
        assertEquals(true, utility.get("flagged"));
    }

    @Test void a_bad_bill_is_refused_with_the_line_number() {
        var token = data.loginAsConfigAdmin(client);
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/reports/meta-billing", "month,category,amount_inr\n2026-09,promo,10\n")
                        .contentType(MediaType.of("text/csv")).bearerAuth(token), Map.class));
        assertEquals(400, e.getStatus().getCode());
        assertTrue(e.getResponse().getBody(String.class).orElse("").contains("line 2"));
        var viewer = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/reports/meta-billing", "2026-09,marketing,10")
                        .contentType(MediaType.of("text/csv")).bearerAuth(data.loginAsAnalyst(client)), Map.class));
        assertEquals(403, viewer.getStatus().getCode());
    }
}
