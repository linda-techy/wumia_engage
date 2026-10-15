package in.brand.engage.admin.reports;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measurement (P7-T03).
 *
 * <ul>
 * <li>{@code GET /api/reports/lift?days=56}: every journey against its holdout
 *     (P5-T09): people in each group, revenue per person since they entered
 *     the experiment, lift with a 95% interval (Welch, {@link LiftStats}),
 *     incremental revenue, messaging cost, ROI. "Not enough data" until the
 *     experiment is 4 weeks old and each group has 30 people. ANALYST.</li>
 * <li>{@code GET /api/journeys/{key}/incrementality?days=30}: one journey (07-api-contract). ANALYST.</li>
 * <li>{@code POST /api/reports/meta-billing}: Meta's WhatsApp bill for a month,
 *     as CSV {@code month,category,amount_inr}. CONFIG_ADMIN, audited.</li>
 * <li>{@code GET /api/reports/spend-reconciliation?month=YYYY-MM}: our ledger
 *     against that bill, per category; over 1% is flagged. VIEWER.</li>
 * </ul>
 * Money stays in paise; the UI formats it.
 */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class ReportsController {

    static final Duration MIN_AGE = Duration.ofDays(28);
    static final int MIN_GROUP = 30;
    static final double FLAG_PCT = 1.0;

    @Serdeable
    public record Week(LocalDate weekIst, long treatedRevenuePaise, long treatedPeople, long controlRevenuePaise, long controlPeople) {}

    @Serdeable
    public record JourneyLift(String journey, OffsetDateTime firstAssigned, boolean enoughData, String why,
                              int treated, int control, double revenuePerTreatedPaise, double revenuePerControlPaise,
                              Double liftPct, Double liftLowPct, Double liftHighPct, boolean significant,
                              long incrementalPaise, long incrementalLowPaise, long incrementalHighPaise,
                              long messagingCostPaise, Double roi, List<Week> weekly) {}

    @Serdeable
    public record ReconciliationRow(String category, long ledgerPaise, Long metaPaise, Double diffPct, boolean flagged) {}

    @Serdeable
    public record Reconciliation(String month, boolean billUploaded, List<ReconciliationRow> rows) {}

    @Serdeable
    public record Uploaded(String month, int rows) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;

    public ReportsController(Db db, CurrentOperator current, AuditLog audit) {
        this.db = db;
        this.current = current;
        this.audit = audit;
    }

    @RequiresRole(Role.ANALYST)
    @Get("/api/reports/lift")
    public List<JourneyLift> lift(@QueryValue(defaultValue = "56") int days) {
        var window = window(days);
        return db.inTx(c -> {
            var experiments = new ArrayList<String>();
            try (var ps = Sql.prepare(c, "SELECT DISTINCT experiment FROM holdouts ORDER BY 1"); var rs = ps.executeQuery()) {
                while (rs.next()) experiments.add(rs.getString(1));
            }
            var out = new ArrayList<JourneyLift>();
            for (var e : experiments) out.add(journey(c, e, window));
            return out;
        });
    }

    @RequiresRole(Role.ANALYST)
    @Get("/api/journeys/{key}/incrementality")
    public JourneyLift incrementality(@PathVariable String key, @QueryValue(defaultValue = "30") int days) {
        var window = window(days);
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM holdouts WHERE experiment = ? LIMIT 1", key); var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no holdout for journey " + key + " yet");
            }
            return journey(c, key, window);
        });
    }

    private Instant window(int days) {
        if (days < 7 || days > 365) throw Problems.badRequest("days is between 7 and 365");
        return Instant.now().minus(Duration.ofDays(days));
    }

    private JourneyLift journey(Connection c, String experiment, Instant from) throws SQLException {
        var treated = new ArrayList<Double>();
        var control = new ArrayList<Double>();
        OffsetDateTime first = null;
        // Revenue per person in the window, counted only after they entered the experiment;
        // people who bought nothing count as zero.
        try (var ps = Sql.prepare(c, """
                SELECT h.bucket, h.assigned_at,
                       COALESCE((SELECT sum(o.total_paise - o.refunded_paise) FROM orders o
                                  WHERE o.identity_id = h.identity_id AND o.cancelled_at IS NULL
                                    AND o.created_at >= GREATEST(h.assigned_at, ?)), 0) AS revenue
                  FROM holdouts h WHERE h.experiment = ?""", from.atOffset(ZoneOffset.UTC), experiment);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var at = Sql.timestamp(rs, "assigned_at");
                if (first == null || at.isBefore(first)) first = at;
                (rs.getString("bucket").equals("control") ? control : treated).add(rs.getDouble("revenue"));
            }
        }
        long cost = cost(c, experiment, from);
        var weekly = weekly(c, experiment, from);
        var age = first == null ? Duration.ZERO : Duration.between(first.toInstant(), Instant.now());
        String why = null;
        if (age.compareTo(MIN_AGE) < 0) why = "under 4 weeks of holdout";
        else if (treated.size() < MIN_GROUP || control.size() < MIN_GROUP) why = "fewer than " + MIN_GROUP + " people in a group";
        var r = LiftStats.welch(treated.stream().mapToDouble(Double::doubleValue).toArray(),
                control.stream().mapToDouble(Double::doubleValue).toArray());
        if (r == null) {
            return new JourneyLift(experiment, first, false, why != null ? why : "too few people", treated.size(), control.size(),
                    0, 0, null, null, null, false, 0, 0, 0, cost, null, weekly);
        }
        Double lift = Double.isNaN(r.liftPct()) ? null : r.liftPct();
        Double liftLow = r.meanControl() == 0 ? null : 100 * r.low() / r.meanControl();
        Double liftHigh = r.meanControl() == 0 ? null : 100 * r.high() / r.meanControl();
        long incremental = Math.round(r.diff() * r.nTreated());
        Double roi = cost == 0 ? null : (double) incremental / cost;
        return new JourneyLift(experiment, first, why == null, why, r.nTreated(), r.nControl(), r.meanTreated(), r.meanControl(),
                lift, liftLow, liftHigh, why == null && r.significant(), incremental, Math.round(r.low() * r.nTreated()),
                Math.round(r.high() * r.nTreated()), cost, roi, weekly);
    }

    /** What the journey's messages cost in the window ({@code global}: every marketing send). */
    private static long cost(Connection c, String experiment, Instant from) throws SQLException {
        var at = from.atOffset(ZoneOffset.UTC);
        try (var ps = experiment.equals("global")
                ? Sql.prepare(c, "SELECT COALESCE(sum(cost_paise), 0) FROM sends WHERE created_at >= ? AND category = 'marketing'"
                        + " AND (intent_key IS NULL OR intent_key NOT LIKE 'campaign:%')", at)
                : Sql.prepare(c, "SELECT COALESCE(sum(cost_paise), 0) FROM sends WHERE created_at >= ? AND split_part(intent_key, ':', 1) = ?",
                        at, experiment);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Week> weekly(Connection c, String experiment, Instant from) throws SQLException {
        var out = new ArrayList<Week>();
        try (var ps = Sql.prepare(c, """
                SELECT week_ist,
                       COALESCE(sum(net_revenue_paise) FILTER (WHERE bucket = 'treatment'), 0) AS t_rev,
                       COALESCE(max(identities) FILTER (WHERE bucket = 'treatment'), 0) AS t_n,
                       COALESCE(sum(net_revenue_paise) FILTER (WHERE bucket = 'control'), 0) AS c_rev,
                       COALESCE(max(identities) FILTER (WHERE bucket = 'control'), 0) AS c_n
                  FROM holdout_lift_weekly
                 WHERE experiment = ? AND week_ist >= (? AT TIME ZONE 'Asia/Kolkata')::date - 7
                 GROUP BY week_ist ORDER BY week_ist""", experiment, from.atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Week(rs.getObject("week_ist", LocalDate.class), rs.getLong("t_rev"), rs.getLong("t_n"),
                        rs.getLong("c_rev"), rs.getLong("c_n")));
            }
        }
        return out;
    }

    /* ---------------------------- spend reconciliation ---------------------------- */

    @RequiresRole(Role.CONFIG_ADMIN)
    @Post(value = "/api/reports/meta-billing", consumes = {"text/csv", MediaType.TEXT_PLAIN})
    public Uploaded uploadBilling(@Body String csv) {
        if (csv == null || csv.isBlank()) throw Problems.badRequest("send CSV: month,category,amount_inr");
        record Row(YearMonth month, String category, long paise) {}
        var rows = new ArrayList<Row>();
        int lineNo = 0;
        for (var raw : csv.strip().split("\\R")) {
            lineNo++;
            var line = raw.strip();
            if (line.isEmpty() || (lineNo == 1 && line.toLowerCase(Locale.ROOT).startsWith("month"))) continue;
            var f = line.split(",");
            if (f.length != 3) throw Problems.badRequest("line " + lineNo + ": expected month,category,amount_inr");
            YearMonth month;
            try {
                month = YearMonth.parse(f[0].strip());
            } catch (RuntimeException e) {
                throw Problems.badRequest("line " + lineNo + ": month is YYYY-MM");
            }
            var category = f[1].strip().toLowerCase(Locale.ROOT);
            if (!List.of("marketing", "utility", "authentication", "service").contains(category)) {
                throw Problems.badRequest("line " + lineNo + ": category is marketing, utility, authentication or service");
            }
            BigDecimal inr;
            try {
                inr = new BigDecimal(f[2].strip());
            } catch (NumberFormatException e) {
                throw Problems.badRequest("line " + lineNo + ": amount_inr is a number");
            }
            if (inr.signum() < 0) throw Problems.badRequest("line " + lineNo + ": amount_inr is not negative");
            rows.add(new Row(month, category, inr.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()));
        }
        if (rows.isEmpty()) throw Problems.badRequest("no rows");
        if (rows.stream().map(Row::month).distinct().count() != 1) throw Problems.badRequest("one month per upload");
        var month = rows.getFirst().month();
        var operator = current.id();
        db.inTx(c -> {
            for (var r : rows) {
                Sql.update(c, """
                        INSERT INTO meta_billing (month, category, amount_paise, uploaded_by) VALUES (?, CAST(? AS msg_category), ?, ?)
                        ON CONFLICT (month, category) DO UPDATE
                           SET amount_paise = EXCLUDED.amount_paise, uploaded_by = EXCLUDED.uploaded_by, uploaded_at = now()""",
                        month.atDay(1), r.category(), r.paise(), operator);
            }
            audit.record(c, operator, "meta_billing.upload", "meta_billing", month.toString(), null,
                    "{\"rows\": " + rows.size() + "}");
            return null;
        });
        return new Uploaded(month.toString(), rows.size());
    }

    @RequiresRole(Role.VIEWER)
    @Get("/api/reports/spend-reconciliation")
    public Reconciliation reconciliation(@QueryValue String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw Problems.badRequest("month is YYYY-MM");
        }
        return db.inTx(c -> compare(c, ym));
    }

    /** Our WhatsApp ledger against Meta's bill for a month, per category. */
    public static Reconciliation compare(Connection c, YearMonth month) throws SQLException {
        var rows = new ArrayList<ReconciliationRow>();
        boolean uploaded = false;
        try (var ps = Sql.prepare(c, """
                WITH ledger AS (
                  SELECT category, sum(paise) AS paise FROM spend_ledger
                   WHERE channel = 'whatsapp' AND day >= ? AND day < ? GROUP BY category),
                bill AS (SELECT category, amount_paise FROM meta_billing WHERE month = ?)
                SELECT COALESCE(l.category, b.category)::text AS category, COALESCE(l.paise, 0) AS ledger, b.amount_paise AS meta
                  FROM ledger l FULL JOIN bill b ON b.category = l.category
                 ORDER BY 1""", month.atDay(1), month.plusMonths(1).atDay(1), month.atDay(1));
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                long ledger = rs.getLong("ledger");
                Long meta = rs.getObject("meta") == null ? null : rs.getLong("meta");
                if (meta != null) uploaded = true;
                Double diff = meta == null ? null : meta == 0 ? (ledger == 0 ? 0.0 : 100.0) : 100.0 * (ledger - meta) / meta;
                rows.add(new ReconciliationRow(rs.getString("category"), ledger, meta, diff,
                        diff != null && Math.abs(diff) > FLAG_PCT));
            }
        }
        return new Reconciliation(month.toString(), uploaded, rows);
    }
}
