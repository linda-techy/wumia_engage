package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.EventDispatcher;
import in.brand.engage.worker.TestClock;
import in.brand.engage.worker.WorkerTestBeans;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The welcome series (playbook 2026-10-22 §3): opt-in, day 2, day 5 offer, day 6 WhatsApp offer. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class WelcomeTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject WorkerTestBeans.FakePush push;

    @BeforeEach void reset() {
        clock.setIst("2026-10-23T12:00:00");
        wa.reset();
        push.all.clear();
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            }
            try (var st = c.createStatement()) {
                st.execute("UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL");
                st.execute("UPDATE cascade_runs SET status = 'cancelled', outcome = 'test_reset' WHERE status IN ('active','waiting')");
                st.execute("TRUNCATE config_versions CASCADE");
            }
            setConfig(c, "holdout.global_pct", "0");
            setConfig(c, "holdout.journey_pct", "0");
            setConfig(c, "budget.whatsapp.marketing.daily_paise", "0");
            setConfig(c, "journey.welcome.discount_code", "\"WELCOME10\"");
            setConfig(c, "journey.welcome.discount_label", "\"10% off\"");
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('welcome_offer_v1', 'en', 'welcome_offer_v1', 'marketing', 'marketing', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
            return null;
        });
        config.invalidate();
    }

    @Test void a_new_push_subscriber_gets_welcome_bestsellers_then_the_offer_with_the_code_applied() {
        var who = subscriber(true);
        optIn(who, "push_subscribed");

        assertEquals("Welcome to WUMIKA ✨", push.all.getLast().title(), attempts(who));
        assertTrue(push.all.getLast().url().startsWith("https://"), "opens the bestsellers");
        assertTrue(push.all.getLast().url().contains("/collections/best-sellers"));
        assertTrue(wa.all.isEmpty(), "no WhatsApp welcome for a push opt-in");

        at("2026-10-25T12:01:00");
        assertEquals("Our most-loved pieces 💎", push.all.getLast().title(), attempts(who));

        at("2026-10-28T12:02:00");
        var offer = push.all.getLast();
        assertEquals("10% off on your first order 🎁", offer.title(), attempts(who));
        assertTrue(offer.url().startsWith("https://"), offer.url());
        assertTrue(offer.url().contains("/discount/WELCOME10?redirect="), "applies the code by itself: " + offer.url());

        at("2026-10-29T12:03:00");
        var call = wa.all.getLast().whatsapp();
        assertEquals("welcome_offer_v1", call.name(), attempts(who));
        assertEquals(List.of("Priya", "10% off", "WELCOME10"), call.bodyParams());
        assertEquals(List.of("discount/WELCOME10?redirect=%2Fcollections%2Fbest-sellers"), call.urlButtonSuffixes());
        assertEquals("WELCOME10", call.couponCode(), "copy-code button");
    }

    @Test void a_buyer_is_not_new_and_nobody_is_welcomed_twice() {
        var buyer = subscriber(false);
        db.inTx(c -> Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, '#1', ?, 99900, now())",
                "test-order-" + UUID.randomUUID(), buyer));
        optIn(buyer, "push_subscribed");
        assertNull(run(buyer), "already bought");

        var who = subscriber(false);
        optIn(who, "push_subscribed");
        optIn(who, "push_subscribed");                         // a second browser
        assertEquals(1, runs(who));
    }

    @Test void the_first_order_ends_the_series() {
        var who = subscriber(false);
        optIn(who, "push_subscribed");
        optIn(who, "order_placed");
        assertEquals("cancelled|order_placed", run(who));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void optIn(UUID who, String name) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, '{}'::jsonb, 'storefront', ?)",
                who, name, clock.instant().atOffset(ZoneOffset.UTC)));
        dispatcher.dispatchOnce();
        orchestrator.tick(200);
    }

    private void at(String istDateTime) {
        clock.setIst(istDateTime);
        orchestrator.tick(200);
    }

    private UUID subscriber(boolean waMarketing) {
        var id = UUID.randomUUID();
        var phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'browse', 'push_v3')""", id, "tok-" + UUID.randomUUID());
            consent(c, id, "push");
            if (waMarketing) consent(c, id, "whatsapp");
            return null;
        });
        return id;
    }

    private static void consent(java.sql.Connection c, UUID id, String channel) throws SQLException {
        Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), 'marketing', 'granted', 'test', now() - interval '1 day')""", id, channel);
    }

    private String run(UUID who) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE intent_key = 'welcome' AND subject_key = ? ORDER BY id DESC LIMIT 1""", who.toString());
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private long runs(UUID who) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM cascade_runs WHERE intent_key = 'welcome' AND subject_key = ?", who.toString());
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String attempts(UUID who) {
        return db.inTx(c -> {
            var out = new StringBuilder(run(who) + " / ");
            try (var ps = Sql.prepare(c, """
                    SELECT a.step_index || ':' || a.channel || ':' || a.result || ':' || COALESCE(a.reason, '')
                           || ' (' || COALESCE(s.failed_reason, s.status::text, '') || ')'
                      FROM cascade_runs r LEFT JOIN cascade_attempts a ON a.run_id = r.id LEFT JOIN sends s ON s.id = a.send_id
                     WHERE r.intent_key = 'welcome' AND r.subject_key = ? ORDER BY a.id""", who.toString()); var rs = ps.executeQuery()) {
                while (rs.next()) out.append(rs.getString(1)).append("; ");
            }
            return out.toString();
        });
    }

    private static void setConfig(java.sql.Connection c, String key, String jsonValue) throws SQLException {
        UUID operator;
        try (var ps = Sql.prepare(c, """
                INSERT INTO operators (email, full_name, password_hash, status)
                VALUES ('worker-test@example.com', 'Worker Test', 'x', 'active')
                ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name RETURNING id""");
             var rs = ps.executeQuery()) {
            rs.next();
            operator = rs.getObject(1, UUID.class);
        }
        Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                VALUES (?, '*', ?::jsonb, ?, 'test')""", key, jsonValue, operator);
    }
}
