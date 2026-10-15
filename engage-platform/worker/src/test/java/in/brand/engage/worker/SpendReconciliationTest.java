package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T03: last month's WhatsApp ledger against Meta's bill. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class SpendReconciliationTest {

    static final YearMonth MONTH = YearMonth.of(2026, 9);

    @Inject SpendReconciliation reconciliation;
    @Inject Db db;

    @BeforeEach void reset() {
        db.inTx(c -> Sql.update(c, "TRUNCATE spend_ledger, meta_billing, spend_reconciliations"));
    }

    @Test void no_bill_means_nothing_checked() {
        assertTrue(reconciliation.run(MONTH).isEmpty());
        assertFalse(reconciliation.checked(MONTH));
    }

    @Test void each_category_is_recorded_and_more_than_one_percent_off_alerts() {
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO spend_ledger (day, channel, category, messages, paise) VALUES
                      ('2026-09-03', 'whatsapp', 'marketing', 500, 430000),
                      ('2026-09-28', 'whatsapp', 'marketing', 500, 430000),
                      ('2026-10-01', 'whatsapp', 'marketing', 999, 999999),
                      ('2026-09-10', 'whatsapp', 'utility', 2000, 230000),
                      ('2026-09-10', 'sms', 'utility', 2000, 50000)""");
            return Sql.update(c, """
                    INSERT INTO meta_billing (month, category, amount_paise) VALUES
                      ('2026-09-01', 'marketing', 865000), ('2026-09-01', 'utility', 240000)""");
        });

        var rows = reconciliation.run(MONTH);
        assertEquals(2, rows.size());
        var marketing = rows.get(0);
        assertEquals("marketing", marketing.category());
        assertEquals(860000, marketing.ledgerPaise(), "September only");
        assertFalse(marketing.alert(), "0.58% off");
        var utility = rows.get(1);
        assertEquals(230000, utility.ledgerPaise(), "WhatsApp only, not SMS");
        assertTrue(utility.alert(), "4.17% off");
        assertTrue(reconciliation.checked(MONTH));

        String stored = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT diff_pct::text FROM spend_reconciliations WHERE category = 'utility'");
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertEquals("-4.17", stored);
        assertEquals(2, reconciliation.run(MONTH).size(), "a rerun updates, not duplicates");
    }
}
