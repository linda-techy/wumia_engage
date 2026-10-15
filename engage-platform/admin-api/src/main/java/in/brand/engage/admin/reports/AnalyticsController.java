package in.brand.engage.admin.reports;

import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Analytics: how messages perform. VIEWER: counts and money, never a person.
 *
 * <ul>
 * <li>{@code GET /api/analytics/overview?from=&to=}: by channel and by journey
 *     (sent, delivered, read, clicked, failed, blocked, cost, orders, revenue),
 *     and a daily series per channel.</li>
 * <li>{@code GET /api/analytics/campaigns?from=&to=}: each campaign started in
 *     the range, with the same funnel.</li>
 * <li>{@code GET /api/campaigns/{id}/report}: one campaign by channel (main and
 *     follow-up), plus its holdout: revenue per person, messaged against held out.</li>
 * <li>{@code GET /api/reports/spend}, {@code /api/reports/blocks},
 *     {@code /api/reports/quality}: spend by day, why messages were not sent,
 *     and WhatsApp number and template quality.</li>
 * </ul>
 * Dates are IST days ({@code yyyy-MM-dd}, inclusive), 30 days by default, at
 * most 92. Revenue is the order total less refunds, cancelled orders left out,
 * credited to a message by V41's last-touch rule (click, else view).
 */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class AnalyticsController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MAX_DAYS = 92;
    /** A send's journey: its intent, with every campaign grouped as "campaigns". */
    static final String JOURNEY = """
            CASE WHEN s.intent_key LIKE 'campaign:%' THEN 'campaigns' ELSE COALESCE(split_part(s.intent_key, ':', 1), 'other') END""";

    @Serdeable
    public record Funnel(String key, long sent, long delivered, long read, long clicked, long failed, long blocked,
                         long costPaise, long orders, long revenuePaise, long clickOrders, long viewOrders) {}

    @Serdeable
    public record Day(LocalDate day, String channel, long sent, long delivered, long clicked, long orders, long revenuePaise) {}

    @Serdeable
    public record Overview(LocalDate from, LocalDate to, Funnel total, List<Funnel> channels, List<Funnel> journeys, List<Day> daily,
                           int clickDays, int viewHours) {}

    @Serdeable
    public record CampaignRow(String id, String name, String channel, String status, OffsetDateTime startedAt,
                              long recipients, Funnel funnel) {}

    @Serdeable
    public record Holdout(long treated, long control, long treatedBuyers, long controlBuyers,
                          double revenuePerTreatedPaise, double revenuePerControlPaise, Double liftPct,
                          Double liftLowPct, Double liftHighPct, boolean enoughData) {}

    @Serdeable
    public record CampaignReport(String id, String name, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                 long recipients, Funnel total, List<Funnel> channels, Holdout holdout, int windowDays) {}

    @Serdeable
    public record SpendDay(LocalDate day, String channel, String category, long messages, long paise) {}

    @Serdeable
    public record BlockDay(LocalDate day, String channel, String status, String reason, long sends) {}

    @Serdeable
    public record NumberQuality(String phoneNumberId, String quality, String messagingTier, String event, OffsetDateTime observedAt) {}

    @Serdeable
    public record TemplateQuality(String key, String language, String status, String quality, String requestedCategory,
                                  String approvedCategory, OffsetDateTime syncedAt) {}

    @Serdeable
    public record Quality(List<NumberQuality> numbers, List<TemplateQuality> templates) {}

    private final Db db;

    public AnalyticsController(Db db) {
        this.db = db;
    }

    /* ------------------------------ ranges ------------------------------ */

    record Range(LocalDate from, LocalDate to) {
        OffsetDateTime start() {
            return from.atStartOfDay(IST).toOffsetDateTime();
        }

        OffsetDateTime end() {
            return to.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        }
    }

    static Range range(@Nullable String from, @Nullable String to) {
        try {
            var today = LocalDate.now(IST);
            var t = to == null || to.isBlank() ? today : LocalDate.parse(to);
            var f = from == null || from.isBlank() ? t.minusDays(29) : LocalDate.parse(from);
            if (f.isAfter(t)) throw Problems.badRequest("from is after to");
            if (f.plusDays(MAX_DAYS - 1).isBefore(t)) throw Problems.badRequest("at most " + MAX_DAYS + " days");
            return new Range(f, t);
        } catch (DateTimeParseException e) {
            throw Problems.badRequest("dates are yyyy-MM-dd");
        }
    }

    /* ------------------------------ funnels ----------------------------- */

    /**
     * The funnel grouped by {@code groupExpr} over sends created in the range,
     * plus the orders credited to those sends. {@code where} narrows the sends.
     */
    private static List<Funnel> funnels(Connection c, String groupExpr, String where, Object... params) throws SQLException {
        var sql = """
                WITH s AS (
                  SELECT s.*, %1$s AS g FROM sends s WHERE %2$s
                ), f AS (
                  SELECT g,
                         count(*) FILTER (WHERE sent_at IS NOT NULL) AS sent,
                         count(*) FILTER (WHERE delivered_at IS NOT NULL) AS delivered,
                         count(*) FILTER (WHERE read_at IS NOT NULL) AS read,
                         count(*) FILTER (WHERE clicked_at IS NOT NULL) AS clicked,
                         count(*) FILTER (WHERE status = 'failed') AS failed,
                         count(*) FILTER (WHERE status = 'blocked') AS blocked,
                         COALESCE(sum(cost_paise), 0) AS cost
                    FROM s GROUP BY g
                ), o AS (
                  SELECT s.g, count(*) AS orders,
                         COALESCE(sum(COALESCE(od.total_paise - od.refunded_paise, cv.value_paise)), 0) AS revenue,
                         count(*) FILTER (WHERE cv.attribution = 'click') AS click_orders,
                         count(*) FILTER (WHERE cv.attribution = 'view') AS view_orders
                    FROM conversions cv
                    JOIN s ON s.id = cv.last_send_id
                    LEFT JOIN orders od ON od.id = cv.order_id
                   WHERE od.cancelled_at IS NULL
                   GROUP BY s.g
                )
                SELECT f.g, f.sent, f.delivered, f.read, f.clicked, f.failed, f.blocked, f.cost,
                       COALESCE(o.orders, 0) AS orders, COALESCE(o.revenue, 0) AS revenue,
                       COALESCE(o.click_orders, 0) AS click_orders, COALESCE(o.view_orders, 0) AS view_orders
                  FROM f LEFT JOIN o ON o.g = f.g
                 ORDER BY f.sent DESC, f.g""".formatted(groupExpr, where);
        var out = new ArrayList<Funnel>();
        try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
            while (rs.next()) out.add(funnel(rs, rs.getString("g")));
        }
        return out;
    }

    private static Funnel funnel(ResultSet rs, String key) throws SQLException {
        return new Funnel(key, rs.getLong("sent"), rs.getLong("delivered"), rs.getLong("read"), rs.getLong("clicked"),
                rs.getLong("failed"), rs.getLong("blocked"), rs.getLong("cost"), rs.getLong("orders"), rs.getLong("revenue"),
                rs.getLong("click_orders"), rs.getLong("view_orders"));
    }

    static Funnel sum(String key, List<Funnel> rows) {
        long[] t = new long[11];
        for (var f : rows) {
            long[] v = {f.sent(), f.delivered(), f.read(), f.clicked(), f.failed(), f.blocked(), f.costPaise(), f.orders(),
                    f.revenuePaise(), f.clickOrders(), f.viewOrders()};
            for (int i = 0; i < t.length; i++) t[i] += v[i];
        }
        return new Funnel(key, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10]);
    }

    /* ------------------------------ overview ---------------------------- */

    @RequiresRole(Role.VIEWER)
    @Get("/api/analytics/overview")
    public Overview overview(@Nullable @QueryValue String from, @Nullable @QueryValue String to) {
        var r = range(from, to);
        return db.inTx(c -> {
            var channels = funnels(c, "s.channel::text", "s.created_at >= ? AND s.created_at < ?", r.start(), r.end());
            var journeys = funnels(c, JOURNEY, "s.created_at >= ? AND s.created_at < ?", r.start(), r.end());
            var daily = new ArrayList<Day>();
            try (var ps = Sql.prepare(c, """
                    WITH s AS (
                      SELECT s.*, (s.created_at AT TIME ZONE 'Asia/Kolkata')::date AS d FROM sends s
                       WHERE s.created_at >= ? AND s.created_at < ?
                    ), o AS (
                      SELECT s.d, s.channel, count(*) AS orders,
                             COALESCE(sum(COALESCE(od.total_paise - od.refunded_paise, cv.value_paise)), 0) AS revenue
                        FROM conversions cv JOIN s ON s.id = cv.last_send_id
                        LEFT JOIN orders od ON od.id = cv.order_id
                       WHERE od.cancelled_at IS NULL
                       GROUP BY 1, 2
                    )
                    SELECT s.d, s.channel::text AS channel,
                           count(*) FILTER (WHERE sent_at IS NOT NULL) AS sent,
                           count(*) FILTER (WHERE delivered_at IS NOT NULL) AS delivered,
                           count(*) FILTER (WHERE clicked_at IS NOT NULL) AS clicked,
                           COALESCE(max(o.orders), 0) AS orders, COALESCE(max(o.revenue), 0) AS revenue
                      FROM s LEFT JOIN o ON o.d = s.d AND o.channel = s.channel
                     GROUP BY s.d, s.channel ORDER BY s.d, s.channel""", r.start(), r.end());
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    daily.add(new Day(rs.getObject("d", LocalDate.class), rs.getString("channel"), rs.getLong("sent"),
                            rs.getLong("delivered"), rs.getLong("clicked"), rs.getLong("orders"), rs.getLong("revenue")));
                }
            }
            return new Overview(r.from(), r.to(), sum("all", channels), channels, journeys, daily,
                    setting(c, "attribution.click_days"), setting(c, "attribution.view_hours"));
        });
    }

    private static int setting(Connection c, String key) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT config_int(?)", key); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /* ------------------------------ campaigns --------------------------- */

    @RequiresRole(Role.VIEWER)
    @Get("/api/analytics/campaigns")
    public List<CampaignRow> campaigns(@Nullable @QueryValue String from, @Nullable @QueryValue String to) {
        var r = range(from, to);
        return db.inTx(c -> {
            var funnels = funnels(c, "substring(s.intent_key FROM 10)",
                    "s.intent_key IN (SELECT 'campaign:' || id FROM campaigns WHERE started_at >= ? AND started_at < ?)",
                    r.start(), r.end());
            var out = new ArrayList<CampaignRow>();
            try (var ps = Sql.prepare(c, """
                    SELECT k.id::text AS id, k.name, k.channel::text AS channel, k.status, k.started_at,
                           (SELECT count(*) FROM campaign_recipients cr WHERE cr.campaign_id = k.id AND cr.bucket = 'treatment') AS recipients
                      FROM campaigns k WHERE k.started_at >= ? AND k.started_at < ?
                     ORDER BY k.started_at DESC""", r.start(), r.end());
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var id = rs.getString("id");
                    var f = funnels.stream().filter(x -> id.equals(x.key())).findFirst()
                            .orElse(new Funnel(id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
                    out.add(new CampaignRow(id, rs.getString("name"), rs.getString("channel"), rs.getString("status"),
                            Sql.timestamp(rs, "started_at"), rs.getLong("recipients"), f));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.VIEWER)
    @Get("/api/campaigns/{id}/report")
    public CampaignReport campaign(@PathVariable String id) {
        UUID campaignId;
        try {
            campaignId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no campaign " + id);
        }
        return db.inTx(c -> {
            String name;
            String status;
            OffsetDateTime started;
            OffsetDateTime finished;
            try (var ps = Sql.prepare(c, "SELECT name, status, started_at, finished_at FROM campaigns WHERE id = ?", campaignId);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no campaign " + id);
                name = rs.getString(1);
                status = rs.getString(2);
                started = Sql.timestamp(rs, "started_at");
                finished = Sql.timestamp(rs, "finished_at");
            }
            var channels = funnels(c, "s.channel::text", "s.intent_key = ?", "campaign:" + campaignId);
            int window = setting(c, "attribution.click_days");
            var holdout = holdout(c, campaignId, started, window);
            long recipients = holdout.treated();
            return new CampaignReport(id, name, status, started, finished, recipients, sum("all", channels), channels, holdout, window);
        });
    }

    /**
     * Messaged against held out, over the attribution window after the start:
     * revenue per person and how many bought, with a Welch interval on the
     * difference ({@link LiftStats}). Needs 30 in each group to be read.
     */
    private static Holdout holdout(Connection c, UUID campaignId, OffsetDateTime started, int days) throws SQLException {
        var treated = new ArrayList<Double>();
        var control = new ArrayList<Double>();
        if (started != null) {
            try (var ps = Sql.prepare(c, """
                    SELECT cr.bucket,
                           COALESCE((SELECT sum(o.total_paise - o.refunded_paise) FROM orders o
                                      WHERE o.identity_id = cr.identity_id AND o.cancelled_at IS NULL
                                        AND o.created_at >= ? AND o.created_at < ? + make_interval(days => ?)), 0) AS revenue
                      FROM campaign_recipients cr WHERE cr.campaign_id = ?""", started, started, days, campaignId);
                 var rs = ps.executeQuery()) {
                while (rs.next()) (rs.getString(1).equals("control") ? control : treated).add(rs.getDouble(2));
            }
        }
        long tBuyers = treated.stream().filter(v -> v > 0).count();
        long cBuyers = control.stream().filter(v -> v > 0).count();
        double tMean = treated.isEmpty() ? 0 : treated.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double cMean = control.isEmpty() ? 0 : control.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var w = LiftStats.welch(treated.stream().mapToDouble(Double::doubleValue).toArray(),
                control.stream().mapToDouble(Double::doubleValue).toArray());
        boolean enough = treated.size() >= 30 && control.size() >= 30;
        Double lift = w == null || Double.isNaN(w.liftPct()) ? null : w.liftPct();
        Double low = w == null || w.meanControl() == 0 ? null : 100 * w.low() / w.meanControl();
        Double high = w == null || w.meanControl() == 0 ? null : 100 * w.high() / w.meanControl();
        return new Holdout(treated.size(), control.size(), tBuyers, cBuyers, tMean, cMean, lift, low, high, enough);
    }

    /* ------------------------------ spend, blocks, quality -------------- */

    @RequiresRole(Role.VIEWER)
    @Get("/api/reports/spend")
    public List<SpendDay> spend(@Nullable @QueryValue String from, @Nullable @QueryValue String to) {
        var r = range(from, to);
        return db.inTx(c -> {
            var out = new ArrayList<SpendDay>();
            try (var ps = Sql.prepare(c, """
                    SELECT day, channel::text, category::text, messages, paise FROM spend_ledger
                     WHERE day BETWEEN ? AND ? ORDER BY day, channel, category""", r.from(), r.to());
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SpendDay(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.VIEWER)
    @Get("/api/reports/blocks")
    public List<BlockDay> blocks(@Nullable @QueryValue String from, @Nullable @QueryValue String to) {
        var r = range(from, to);
        return db.inTx(c -> {
            var out = new ArrayList<BlockDay>();
            try (var ps = Sql.prepare(c, """
                    SELECT (created_at AT TIME ZONE 'Asia/Kolkata')::date AS d, channel::text, status::text,
                           COALESCE(decision->>'reason', 'unknown') AS reason, count(*)
                      FROM sends
                     WHERE created_at >= ? AND created_at < ? AND status IN ('blocked', 'deferred')
                     GROUP BY 1, 2, 3, 4 ORDER BY 1, 5 DESC""", r.start(), r.end());
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BlockDay(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.VIEWER)
    @Get("/api/reports/quality")
    public Quality quality(@Nullable @QueryValue String from, @Nullable @QueryValue String to) {
        var r = range(from, to);
        return db.inTx(c -> {
            var numbers = new ArrayList<NumberQuality>();
            try (var ps = Sql.prepare(c, """
                    SELECT phone_number_id, quality, messaging_tier, event, observed_at FROM wa_phone_number_history
                     WHERE observed_at >= ? AND observed_at < ? ORDER BY observed_at""", r.start(), r.end());
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    numbers.add(new NumberQuality(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            Sql.timestamp(rs, "observed_at")));
                }
            }
            var templates = new ArrayList<TemplateQuality>();
            try (var ps = Sql.prepare(c, """
                    SELECT key, language, status, quality, requested_category::text, approved_category::text, synced_at
                      FROM wa_templates ORDER BY key, language""");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    templates.add(new TemplateQuality(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), Sql.timestamp(rs, "synced_at")));
                }
            }
            return new Quality(numbers, templates);
        });
    }
}
