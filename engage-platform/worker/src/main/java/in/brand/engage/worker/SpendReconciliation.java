package in.brand.engage.worker;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our WhatsApp spend ledger against Meta's bill for the month (P7-T03). The
 * bill is uploaded by an admin ({@code POST /api/reports/meta-billing}); each
 * category is recorded in {@code spend_reconciliations}, and one more than 1%
 * off is an ERROR, which the log alerting turns into a page. A month with no
 * bill yet is a WARN: the check runs again the next day until one arrives.
 */
@Singleton
public class SpendReconciliation {

    private static final Logger LOG = LoggerFactory.getLogger(SpendReconciliation.class);
    static final double ALERT_PCT = 1.0;

    public record Row(String category, long ledgerPaise, long providerPaise, double diffPct, boolean alert) {}

    private final Db db;

    public SpendReconciliation(Db db) {
        this.db = db;
    }

    /** @return the categories compared; empty when the month's bill is not uploaded */
    public List<Row> run(YearMonth month) {
        var rows = db.inTx(c -> {
            var out = new ArrayList<Row>();
            try (var ps = Sql.prepare(c, """
                    SELECT b.category::text AS category, COALESCE(l.paise, 0) AS ledger, b.amount_paise AS provider
                      FROM meta_billing b
                      LEFT JOIN (SELECT category, sum(paise) AS paise FROM spend_ledger
                                  WHERE channel = 'whatsapp' AND day >= ? AND day < ? GROUP BY category) l
                             ON l.category = b.category
                     WHERE b.month = ?
                     ORDER BY 1""", month.atDay(1), month.plusMonths(1).atDay(1), month.atDay(1));
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ledger = rs.getLong("ledger");
                    long provider = rs.getLong("provider");
                    double diff = provider == 0 ? (ledger == 0 ? 0 : 100) : 100.0 * (ledger - provider) / provider;
                    out.add(new Row(rs.getString("category"), ledger, provider, diff, Math.abs(diff) > ALERT_PCT));
                }
            }
            for (var r : out) {
                Sql.update(c, """
                        INSERT INTO spend_reconciliations (month, category, ledger_paise, provider_paise, diff_pct)
                        VALUES (?, CAST(? AS msg_category), ?, ?, ?)
                        ON CONFLICT (month, category) DO UPDATE
                           SET ledger_paise = EXCLUDED.ledger_paise, provider_paise = EXCLUDED.provider_paise,
                               diff_pct = EXCLUDED.diff_pct, checked_at = now()""",
                        month.atDay(1), r.category(), r.ledgerPaise(), r.providerPaise(),
                        java.math.BigDecimal.valueOf(r.diffPct()).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            return out;
        });
        if (rows.isEmpty()) {
            LOG.warn("spend reconciliation {}: Meta's WhatsApp bill is not uploaded yet (Reports, Spend reconciliation)", month);
        }
        for (var r : rows) {
            if (r.alert()) {
                LOG.error("spend reconciliation {} {}: ledger ₹{} vs Meta ₹{}, {}% off (alert over {}%)", month, r.category(),
                        r.ledgerPaise() / 100.0, r.providerPaise() / 100.0, String.format("%.2f", r.diffPct()), ALERT_PCT);
            } else {
                LOG.info("spend reconciliation {} {}: within {}% ({}%)", month, r.category(), ALERT_PCT,
                        String.format("%.2f", r.diffPct()));
            }
        }
        return rows;
    }

    /** Whether the month has been checked against an uploaded bill. */
    public boolean checked(YearMonth month) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM spend_reconciliations WHERE month = ? LIMIT 1", month.atDay(1));
                 var rs = ps.executeQuery()) {
                return rs.next();
            }
        });
    }
}
