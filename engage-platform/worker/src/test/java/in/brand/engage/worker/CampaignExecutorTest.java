package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T06 executor against Postgres: rate, pause, budget cap, follow-up. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CampaignExecutorTest {

    static final String TEMPLATE = "push_campaign_new_arrivals_v1";

    @Inject CampaignExecutor executor;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakePush push;

    UUID operator;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            }
            try (var st = c.createStatement()) {
                st.execute("UPDATE campaigns SET status = 'CANCELLED' WHERE status IN ('RUNNING', 'SCHEDULED')");
                st.execute("UPDATE cascade_runs SET status = 'cancelled', outcome = 'test_reset' WHERE status IN ('active','waiting')");
                st.execute("TRUNCATE config_versions CASCADE");
            }
            operator = operator(c);
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.global_pct', '*', '0', ?, 'test')""", operator);
            return null;
        });
        config.invalidate();
        push.all.clear();
    }

    @Test void an_armed_campaign_reaches_every_recipient_through_the_orchestrator_then_completes() {
        var campaign = campaign(6000, null, null);
        var people = recipients(campaign, 3);

        assertEquals(3, executor.runOnce());
        assertEquals(3, push.all.size());
        assertEquals(3L, count("SELECT count(*) FROM campaign_recipients WHERE campaign_id = '" + campaign
                + "' AND state = 'sent' AND send_id IS NOT NULL"));
        assertEquals(3L, count("SELECT count(*) FROM sends WHERE intent_key = 'campaign:" + campaign + "' AND status = 'sent'"));

        executor.runOnce();
        assertEquals("COMPLETED", status(campaign));
        assertEquals(people.size(), push.all.size(), "nobody twice");
    }

    @Test void the_executor_never_exceeds_the_rate_by_more_than_one_batch() {
        var campaign = campaign(10, null, null);           // bucket holds 10, refills 1 every 6 s
        recipients(campaign, 30);

        int first = executor.runOnce();
        int second = executor.runOnce();
        assertEquals(10, first, "one bucket's worth");
        assertTrue(second <= 1, "an immediate second pass finds the bucket empty, got " + second);
    }

    @Test void a_pause_stops_the_run_within_one_batch() {
        var campaign = campaign(10, null, null);
        recipients(campaign, 30);
        assertEquals(10, executor.runOnce());

        db.inTx(c -> Sql.update(c, "UPDATE campaigns SET status = 'PAUSED', paused_reason = 'operator' WHERE id = ?", campaign));
        db.inTx(c -> Sql.update(c, "UPDATE campaign_rate_buckets SET tokens = capacity WHERE campaign_id = ?", campaign));

        assertEquals(0, executor.runOnce());
        assertEquals(20L, count("SELECT count(*) FROM campaign_recipients WHERE campaign_id = '" + campaign + "' AND state = 'pending'"));
    }

    @Test void the_budget_cap_stops_the_run() {
        var campaign = campaign(6000, 1000L, null);
        recipients(campaign, 5);
        var spender = subscriber();
        db.inTx(c -> Sql.update(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status, cost_paise)
                VALUES (?, 'whatsapp', 'marketing', 'x', ?, ?, 'sent', 1000)""",
                spender, "campaign:" + campaign, "budget-test-" + campaign));

        assertEquals(0, executor.runOnce());
        assertEquals("PAUSED", status(campaign));
        assertEquals("budget_cap", text("SELECT paused_reason FROM campaigns WHERE id = '" + campaign + "'"));
        assertEquals(0, push.all.size());
    }

    @Test void a_follow_up_campaign_waits_to_send_its_second_step() {
        var campaign = campaign(6000, null, 360);         // push now, WhatsApp 6 h later to non-clickers
        var person = recipients(campaign, 1).getFirst();

        executor.runOnce();
        assertEquals(1, push.all.size());
        assertEquals("waiting|1", text("""
                SELECT status || '|' || step_index FROM cascade_runs
                 WHERE intent_key = 'campaign:%s' AND identity_id = '%s'""".formatted(campaign, person)));
    }

    /* -------------------------------- fixtures -------------------------------- */

    UUID campaign(int ratePerMinute, Long budgetCap, Integer followUpMinutes) {
        return db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, status) VALUES (?, 'push', 'marketing', 'active')
                    ON CONFLICT (key) DO NOTHING""", TEMPLATE);
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, status) VALUES ('wa_campaign_test', 'whatsapp', 'marketing', 'active')
                    ON CONFLICT (key) DO NOTHING""");
            UUID id;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO campaigns (name, channel, template_key, vars, status, send_rate_per_minute, budget_cap_paise,
                                           follow_up_channel, follow_up_template_key, follow_up_after_minutes,
                                           created_by, started_at)
                    VALUES ('test', 'push', ?, '{"collection":"Linen","url":"https://w.example/c/linen"}', 'RUNNING', ?, ?,
                            CAST(? AS channel), ?, ?, ?, now()) RETURNING id""",
                    TEMPLATE, ratePerMinute, budgetCap,
                    followUpMinutes == null ? null : "whatsapp", followUpMinutes == null ? null : "wa_campaign_test",
                    followUpMinutes, operator);
                 var rs = ps.executeQuery()) {
                rs.next();
                id = rs.getObject(1, UUID.class);
            }
            int capacity = Math.min(ratePerMinute, CampaignExecutor.BATCH);
            Sql.update(c, """
                    INSERT INTO campaign_rate_buckets (campaign_id, capacity, refill_per_second, tokens)
                    VALUES (?, ?, CAST(? AS numeric), ?)""", id, capacity, String.valueOf(ratePerMinute / 60.0), capacity);
            return id;
        });
    }

    List<UUID> recipients(UUID campaign, int n) {
        var out = new ArrayList<UUID>();
        for (int i = 0; i < n; i++) {
            var id = subscriber();
            db.inTx(c -> Sql.update(c, "INSERT INTO campaign_recipients (campaign_id, identity_id) VALUES (?, ?)", campaign, id));
            out.add(id);
        }
        return out;
    }

    /** Fresh push token and a push marketing grant. */
    UUID subscriber() {
        var id = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver,
                                         last_refreshed_at)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1', now())""",
                    id, "tok-" + UUID.randomUUID());
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'push', 'marketing', 'granted', 'test', '2026-09-01T00:00:00Z')""", id);
            return null;
        });
        return id;
    }

    String status(UUID campaign) {
        return text("SELECT status FROM campaigns WHERE id = '" + campaign + "'");
    }

    long count(String sql) {
        return Long.parseLong(text(sql));
    }

    String text(String sql) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private static UUID operator(java.sql.Connection c) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO operators (email, full_name, password_hash, status)
                VALUES ('worker-test@example.com', 'Worker Test', 'x', 'active')
                ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name RETURNING id""");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }
}
