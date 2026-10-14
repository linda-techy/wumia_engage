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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P5-T08: win-back, from the nightly lapse check to WhatsApp, push and the offer. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class WinbackTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject LapsedCustomers lapsed;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject WorkerTestBeans.FakePush push;
    @Inject WorkerTestBeans.FakeDiscounts discounts;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T01:30:00");
        wa.reset();
        push.all.clear();
        discounts.reset();
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
            for (var t : List.of("winback_v1", "winback_offer_v1")) {
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                        VALUES (?, 'en', ?, 'marketing', 'marketing', 'APPROVED', 'GREEN')
                        ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""", t, t);
            }
            return null;
        });
        config.invalidate();
    }

    /* ------------------------------ lapse check ------------------------------ */

    @Test void a_customer_whose_last_order_just_turned_90_days_old_lapses_once() {
        var who = customer(true);
        order(who, daysAgo(91), 1249900, 0);

        lapsed.detect(clock.instant());
        lapsed.detect(clock.instant());                     // a second worker, or the next night

        assertEquals(1L, lapsedEvents(who));
    }

    @Test void recent_buyers_long_gone_buyers_and_recent_visitors_do_not_lapse() {
        var recent = customer(true);
        order(recent, daysAgo(30), 99900, 0);
        var longGone = customer(true);
        order(longGone, daysAgo(400), 99900, 0);           // lapsed long before: not part of tonight's batch
        var browsing = customer(true);
        order(browsing, daysAgo(91), 99900, 0);
        event(browsing, "cart_updated", "{\"cart_token\":\"x\"}", clock.instant().minus(Duration.ofDays(5)));
        var cancelledOnly = customer(true);
        order(cancelledOnly, daysAgo(91), 99900, 0);
        db.inTx(c -> Sql.update(c, "UPDATE orders SET cancelled_at = now() WHERE identity_id = ?", cancelledOnly));

        lapsed.detect(clock.instant());

        assertEquals(0L, lapsedEvents(recent));
        assertEquals(0L, lapsedEvents(longGone));
        assertEquals(0L, lapsedEvents(browsing), "still visiting: the browse and cart journeys have them");
        assertEquals(0L, lapsedEvents(cancelledOnly), "a cancelled order is not a purchase");
    }

    /* -------------------------------- journey -------------------------------- */

    @Test void whatsapp_at_11_then_push_a_week_later_and_no_offer_without_a_discount() {
        var who = lapsedCustomer(true);

        at("2026-09-28T10:59:00");
        assertTrue(wa.all.isEmpty(), "found at 01:30, sent late morning");
        at("2026-09-28T11:01:00");
        assertFalse(wa.all.isEmpty(), attempts(who));
        var call = wa.all.getLast().whatsapp();
        assertEquals("winback_v1", call.name());
        assertEquals(List.of("Priya"), call.bodyParams());
        assertEquals(List.of("collections/all"), call.urlButtonSuffixes());
        assertTrue(push.all.isEmpty());

        at("2026-10-05T11:02:00");
        assertEquals("New at WUMIKA", push.all.getLast().title());
        at("2026-10-12T11:03:00");
        assertEquals(1, wa.all.size());
        assertEquals("exhausted|skipped:HAS_DISCOUNT", run(who));
    }

    @Test void with_unique_codes_the_offer_carries_a_single_use_code() {
        db.inTx(c -> {
            setConfig(c, "journey.winback.discount_mode", "\"unique\"");
            setConfig(c, "journey.winback.discount_percent", "20");
            return null;
        });
        config.invalidate();
        var who = lapsedCustomer(true);
        at("2026-09-28T11:01:00");
        at("2026-10-05T11:02:00");
        at("2026-10-12T11:03:00");

        assertEquals(1, discounts.created.size());
        var spec = discounts.created.getFirst();
        assertEquals(20, spec.percent());
        assertTrue(spec.title().startsWith("winback "), spec.title());
        assertEquals(List.of("Priya", "20% off", spec.code()), wa.all.getLast().whatsapp().bodyParams());
        assertEquals("exhausted|sent", run(who));
    }

    @Test void customers_who_return_half_of_what_they_buy_get_no_discount() {
        db.inTx(c -> {
            setConfig(c, "journey.winback.discount_mode", "\"unique\"");
            return null;
        });
        config.invalidate();
        var who = customer(true);
        order(who, daysAgo(91), 200000, 120000);            // ₹1,200 of ₹2,000 refunded
        lapsed.detect(clock.instant());
        dispatcher.dispatchOnce();
        assertNull(runVar(who, "discount"), "reminders only");
        assertEquals("active|", run(who));
    }

    @Test void an_order_ends_the_win_back() {
        var who = lapsedCustomer(true);
        at("2026-09-28T11:01:00");
        event(who, "order_placed", "{\"order_id\":\"o1\"}", clock.instant());
        dispatcher.dispatchOnce();
        assertEquals("cancelled|order_placed", run(who));
    }

    @Test void without_a_whatsapp_opt_in_the_push_goes_at_11() {
        var who = lapsedCustomer(false);
        at("2026-09-28T11:01:00");
        assertTrue(wa.all.isEmpty());
        assertEquals(1, push.all.size(), attempts(who));
    }

    @Test void the_send_time_is_late_morning_ist() {
        assertEquals(Instant.parse("2026-09-28T05:30:00Z"), Winback.sendTime(Instant.parse("2026-09-27T20:00:00Z")));
        var afternoon = Instant.parse("2026-09-28T09:00:00Z");
        assertEquals(afternoon, Winback.sendTime(afternoon), "already past 11:00 IST: now");
    }

    /* -------------------------------- fixtures -------------------------------- */

    private UUID lapsedCustomer(boolean waMarketing) {
        var who = customer(waMarketing);
        order(who, daysAgo(91), 1249900, 0);
        lapsed.detect(clock.instant());
        assertEquals(1L, lapsedEvents(who));
        dispatcher.dispatchOnce();
        return who;
    }

    private Instant daysAgo(int days) {
        return clock.instant().minus(Duration.ofDays(days)).minus(Duration.ofHours(1));
    }

    private void at(String istDateTime) {
        clock.setIst(istDateTime);
        orchestrator.tick(200);
    }

    private UUID customer(boolean waMarketing) {
        var id = UUID.randomUUID();
        var phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1')""", id, "tok-" + UUID.randomUUID());
            consent(c, id, "push");
            if (waMarketing) consent(c, id, "whatsapp");
            return null;
        });
        return id;
    }

    private void order(UUID who, Instant at, long totalPaise, long refundedPaise) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO orders (id, order_number, identity_id, total_paise, refunded_paise, created_at)
                VALUES (?, '#1001', ?, ?, ?, ?)""", "test-order-" + UUID.randomUUID(), who, totalPaise, refundedPaise,
                at.atOffset(ZoneOffset.UTC)));
    }

    private static void consent(java.sql.Connection c, UUID id, String channel) throws SQLException {
        Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), 'marketing', 'granted', 'test', now() - interval '400 days')""", id, channel);
    }

    private void event(UUID who, String name, String props, Instant at) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'shopify', ?)",
                who, name, props, at.atOffset(ZoneOffset.UTC)));
    }

    private long lapsedEvents(UUID who) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM events WHERE identity_id = ? AND name = 'became_lapsed'", who);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String run(UUID who) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE intent_key = 'winback' AND subject_key = ? ORDER BY id DESC LIMIT 1""", who.toString());
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private String attempts(UUID who) {
        return db.inTx(c -> {
            var out = new StringBuilder(run(who) + " / ");
            try (var ps = Sql.prepare(c, """
                    SELECT a.step_index || ':' || a.channel || ':' || a.result || ':' || COALESCE(a.reason, '')
                           || ' (' || COALESCE(s.failed_reason, s.status::text, '') || ')' || ' next=' || r.next_step_at
                      FROM cascade_runs r LEFT JOIN cascade_attempts a ON a.run_id = r.id LEFT JOIN sends s ON s.id = a.send_id
                     WHERE r.intent_key = 'winback' AND r.subject_key = ? ORDER BY a.id""", who.toString()); var rs = ps.executeQuery()) {
                while (rs.next()) out.append(rs.getString(1)).append("; ");
            }
            return out.toString();
        });
    }

    private String runVar(UUID who, String key) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT vars->>? FROM cascade_runs WHERE intent_key = 'winback' AND subject_key = ? ORDER BY id DESC LIMIT 1""",
                    key, who.toString());
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private static void setConfig(java.sql.Connection c, String key, String jsonValue) throws SQLException {
        Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                VALUES (?, '*', ?::jsonb, ?, 'test')""", key, jsonValue, operator(c));
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
