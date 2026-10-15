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

/** P5-T06: "how do you like it?" three days after delivery, and the answers to its quick replies. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PostPurchaseTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakeWhatsApp wa;

    String orderId;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T15:00:00");
        wa.reset();
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
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('review_ask_v1', 'en', 'review_ask_v1', 'marketing', 'marketing', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
            Sql.update(c, """
                    INSERT INTO products (product_id, handle, title, updated_at) VALUES ('p-jhumka', 'kundan-jhumka-earrings',
                    'Kundan Jhumka Earrings', now()) ON CONFLICT (product_id) DO NOTHING""");
            return null;
        });
        config.invalidate();
        orderId = "test-order-" + UUID.randomUUID();
    }

    @Test void three_days_after_delivery_at_11_it_asks_how_they_like_it() {
        var who = customer();
        order(who, 0);
        delivered(who);

        at("2026-10-01T10:59:00");
        assertTrue(wa.all.isEmpty());
        at("2026-10-01T11:01:00");
        var call = wa.all.getLast().whatsapp();
        assertEquals("review_ask_v1", call.name());
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings"), call.bodyParams());
        assertEquals(List.of("review_love", "review_help"), call.quickReplyPayloads());
    }

    @Test void once_per_order_however_many_parcels() {
        var who = customer();
        order(who, 0);
        delivered(who);
        delivered(who);
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'post_purchase' AND subject_key = ?",
                "review:" + orderId));
    }

    @Test void refunded_or_refunding_orders_are_not_asked() {
        var who = customer();
        order(who, 50000);
        delivered(who);
        assertEquals(0L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ?", "review:" + orderId));

        orderId = "test-order-" + UUID.randomUUID();
        order(who, 0);
        delivered(who);
        event(who, "refund_initiated", "{\"order_id\":\"" + orderId + "\",\"refund_id\":\"r1\",\"amount_paise\":1000}");
        dispatcher.dispatchOnce();
        at("2026-10-01T11:01:00");
        assertTrue(wa.all.stream().noneMatch(m -> m.whatsapp() != null && m.whatsapp().name().equals("review_ask_v1")),
                "the refund update may go; the review request does not");
        assertEquals("cancelled|refund_initiated", run(who));
    }

    @Test void love_it_gets_a_free_thank_you_with_the_review_link() {
        var who = customer();
        order(who, 0);
        delivered(who);
        at("2026-10-01T11:01:00");
        var wamid = "wamid.test-" + wa.all.getLast().sendId();
        tap(who, "review_love", wamid);

        var reply = wa.all.getLast();
        assertNull(reply.whatsapp(), "a free-form reply in the window the tap opened");
        assertTrue(reply.body().startsWith("Thank you, Priya! You can add a photo or a short review at "), reply.body());
        assertTrue(reply.body().endsWith("/products/kundan-jhumka-earrings to help other shoppers choose."), reply.body());
        assertEquals("succeeded|replied", run(who));
    }

    @Test void need_help_gets_a_reply_and_flags_support() {
        var who = customer();
        order(who, 0);
        delivered(who);
        at("2026-10-01T11:01:00");
        tap(who, "review_help", "wamid.test-" + wa.all.getLast().sendId());

        assertTrue(wa.all.getLast().body().startsWith("Sorry to hear that, Priya."), wa.all.getLast().body());
        assertEquals(1L, count("SELECT count(*) FROM events WHERE identity_id = ? AND name = 'support_requested' "
                + "AND props->>'order_id' = ?", who, orderId));
        assertEquals(1L, count("SELECT count(*) FROM support_requests WHERE identity_id = ? AND kind = 'after_delivery' "
                + "AND order_id = ? AND status = 'open'", who, orderId), "staff see it in the admin console");
    }

    @Test void a_reply_to_a_failed_delivery_is_acknowledged_once() {
        var who = customer();
        long request = count("""
                INSERT INTO support_requests (identity_id, kind, message_text, message_ids)
                VALUES (?, 'delivery', 'Tomorrow after 5pm', ARRAY['m1']) RETURNING id""", who);
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                clock.instant().plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC), who));
        event(who, "delivery_reply", "{\"support_request_id\":" + request + ",\"message_id\":\"m1\"}");
        event(who, "delivery_reply", "{\"support_request_id\":" + request + ",\"message_id\":\"m1-replayed\"}");
        dispatcher.dispatchOnce();

        assertEquals(1, wa.all.size());
        assertEquals("Thanks, Priya. We have passed your message to our delivery team, and they will arrange the next "
                + "delivery attempt.", wa.all.getLast().body());
    }

    @Test void the_send_time_is_11_on_the_third_day() {
        assertEquals(Instant.parse("2026-10-01T05:30:00Z"), PostPurchase.sendTime(Instant.parse("2026-09-28T17:00:00Z"), 3));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void tap(java.util.UUID who, String payload, String contextWamid) {
        // As ingest-api records a quick-reply tap: the 24 h window opens, and an event named by the payload.
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                clock.instant().plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC), who));
        event(who, payload, "{\"message_id\":\"m-" + UUID.randomUUID() + "\",\"type\":\"button\",\"context_wamid\":\""
                + contextWamid + "\"}");
        dispatcher.dispatchOnce();
    }

    private void delivered(UUID who) {
        event(who, "order_delivered", "{\"order_id\":\"" + orderId + "\",\"shipment_id\":1,\"awb\":\"A1\"}");
        dispatcher.dispatchOnce();
    }

    private void at(String istDateTime) {
        clock.setIst(istDateTime);
        orchestrator.tick(200);
    }

    private UUID customer() {
        var id = UUID.randomUUID();
        var phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", id);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'whatsapp', 'marketing', 'granted', 'test', now() - interval '30 days')""", id);
            return null;
        });
        return id;
    }

    private void order(UUID who, long refundedPaise) {
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO orders (id, order_number, identity_id, total_paise, refunded_paise, created_at)
                    VALUES (?, '#1042', ?, 1249900, ?, now() - interval '5 days')""", orderId, who, refundedPaise);
            Sql.update(c, """
                    INSERT INTO order_lines (order_id, line_no, product_id, title, quantity, price_paise)
                    VALUES (?, 0, 'p-pearl', 'Pearl Studs', 1, 79900), (?, 1, 'p-jhumka', 'Kundan Jhumka Earrings', 1, 1170000)""",
                    orderId, orderId);
            return null;
        });
    }

    private void event(UUID who, String name, String props) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'test', ?)",
                who, name, props, clock.instant().atOffset(ZoneOffset.UTC)));
    }

    private String run(UUID who) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE intent_key = 'post_purchase' AND subject_key = ? ORDER BY id DESC LIMIT 1""", "review:" + orderId);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private long count(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
