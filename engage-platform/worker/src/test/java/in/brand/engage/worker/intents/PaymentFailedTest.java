package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.orchestrator.CascadeRun;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P5-T02: payment_failed from a Razorpay failure to WhatsApp, push and SMS. 22:00 IST: utility ignores quiet hours. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PaymentFailedTest {

    static final String RECOVERY = "https://wumika-dev.myshopify.com/checkouts/cn/hWN4abc/recover?key=k123";

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject TrackRef refs;
    @Inject PaymentFailed.PayRef payRef;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject WorkerTestBeans.FakePush push;
    @Inject WorkerTestBeans.FakeSms sms;

    String checkout;
    String paymentId;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T22:00:00");
        wa.reset();
        push.all.clear();
        sms.all.clear();
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
            for (var t : List.of("payment_failed_v1", "payment_failed_r2_v1", "payment_failed_r2b_v1")) {
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                        VALUES (?, 'en', ?, 'utility', 'utility', 'APPROVED', 'GREEN')
                        ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""", t, t);
            }
            return null;
        });
        config.invalidate();
        checkout = "test-checkout-" + UUID.randomUUID();
        paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    @Test void whatsapp_at_5_minutes_push_at_25_follow_up_at_2_hours_then_sms() {
        var who = customer();
        fail(who, "TECHNICAL", clock.instant());

        at("22:04");
        assertTrue(wa.all.isEmpty(), "5 min: most UPI failures are retried at once");
        at("22:06");
        var first = wa.all.getLast().whatsapp();
        assertEquals("payment_failed_v1", first.name());
        assertEquals(List.of("Priya", "₹12,499", paymentId), first.bodyParams());
        assertEquals(List.of("checkouts/cn/hWN4abc/recover?key=k123"), first.urlButtonSuffixes());

        at("22:27");
        assertEquals("Your payment did not go through", push.all.getLast().title());
        clock.setIst("2026-09-29T00:03:00");
        orchestrator.tick(200);
        assertEquals("payment_failed_r2_v1", wa.all.getLast().whatsapp().name());

        clock.setIst("2026-09-29T04:04:00");
        orchestrator.tick(200);
        assertTrue(attempts(checkout).contains("3:sms:failed:dlt_pending"), "the SMS step ran; DLT is pending: " + attempts(checkout));
    }

    @Test void a_cancelled_payment_gets_the_gentler_follow_up() {
        var who = customer();
        fail(who, "CUSTOMER_CANCELLED", clock.instant());
        at("22:06");
        at("22:27");
        clock.setIst("2026-09-29T00:03:00");
        orchestrator.tick(200);
        assertEquals("payment_failed_r2b_v1", wa.all.getLast().whatsapp().name());
    }

    @Test void a_read_whatsapp_means_no_sms() {
        var who = customer();
        fail(who, "TECHNICAL", clock.instant());
        at("22:06");
        db.inTx(c -> Sql.update(c, "UPDATE sends SET read_at = now() WHERE id = ?", wa.all.getLast().sendId()));
        at("22:27");
        clock.setIst("2026-09-29T00:03:00");
        orchestrator.tick(200);
        clock.setIst("2026-09-29T04:04:00");
        orchestrator.tick(200);
        assertTrue(attempts(checkout).contains("3:sms:skipped:guard:WA_UNREAD"), attempts(checkout));
    }

    @Test void a_capture_within_minutes_sends_nothing() {
        var who = customer();
        fail(who, "TECHNICAL", clock.instant());
        clock.setIst("2026-09-28T22:03:00");
        event(who, "payment_captured", "{\"payment_id\":\"pay_other\",\"checkout_token\":\"" + checkout + "\"}", clock.instant());
        dispatcher.dispatchOnce();
        at("22:30");
        assertTrue(wa.all.isEmpty());
        assertEquals("cancelled|payment_captured", run(checkout));
    }

    @Test void a_failure_reported_late_starts_with_the_push() {
        var who = customer();
        fail(who, "TECHNICAL", clock.instant().minus(Duration.ofMinutes(40)));
        orchestrator.tick(200);
        assertTrue(wa.all.isEmpty(), "a WhatsApp 40 minutes late reads as the brand not knowing");
        assertEquals(1, push.all.size(), attempts(checkout));
    }

    @Test void the_failure_takes_the_checkout_over_from_checkout_abandon() {
        var who = customer();
        checkoutRow(who);
        event(who, "checkout_updated", "{\"checkout_token\":\"" + checkout + "\"}", clock.instant());
        dispatcher.dispatchOnce();
        assertEquals("active|", run(checkout, CheckoutAbandon.INTENT));

        fail(who, "TECHNICAL", clock.instant());
        assertEquals("cancelled|payment_failed", run(checkout, CheckoutAbandon.INTENT));

        event(who, "checkout_updated", "{\"checkout_token\":\"" + checkout + "\"}", clock.instant());
        dispatcher.dispatchOnce();
        assertEquals("cancelled|payment_failed", run(checkout, CheckoutAbandon.INTENT), "no abandoned-checkout run after a failure");
    }

    @Test void an_unmatched_failure_still_goes_and_links_to_the_cart() {
        var who = customer();
        event(who, "payment_failed_confirmed", "{\"payment_id\":\"" + paymentId + "\",\"amount_paise\":79900,"
                + "\"match_method\":\"none\",\"failure_kind\":\"TECHNICAL\"}", clock.instant());
        dispatcher.dispatchOnce();
        at("22:06");
        assertEquals(List.of("cart"), wa.all.getLast().whatsapp().urlButtonSuffixes());
        assertNotNull(run("pay:" + paymentId));
    }

    @Test void the_sms_pay_ref_names_the_run_and_is_made_at_send_time() {
        var run = new CascadeRun(4242, PaymentFailed.INTENT, UUID.randomUUID(), "x", 1, 3, "active", null, Instant.now(), 0);
        var made = payRef.provide(run, Map.of());
        assertEquals(4242L, refs.parsePayment(made.get("pay_ref")).orElseThrow());
        assertTrue(made.get("pay_ref").length() <= 30);
        assertEquals(Map.of(), payRef.provide(run, made), "kept on the run: a retry reuses it");
        assertTrue(refs.parse(made.get("pay_ref")).isEmpty(), "a pay ref is not a tracking ref");
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void fail(UUID who, String kind, Instant at) {
        if (count("SELECT count(*) FROM checkouts WHERE token = ?", checkout) == 0) checkoutRow(who);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO payment_attempts (gateway, gateway_payment_id, status, amount_paise, currency, identity_id,
                                              checkout_token, match_method, gateway_created_at)
                VALUES ('razorpay', ?, 'failed', 1249900, 'INR', ?, ?, 'notes_ref', ?)""", paymentId, who, checkout, at.atOffset(ZoneOffset.UTC)));
        event(who, "payment_failed_confirmed", "{\"payment_id\":\"" + paymentId + "\",\"amount_paise\":1249900,"
                + "\"checkout_token\":\"" + checkout + "\",\"match_method\":\"notes_ref\",\"failure_kind\":\"" + kind + "\"}", at);
        dispatcher.dispatchOnce();
    }

    private void checkoutRow(UUID who) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO checkouts (token, identity_id, phone, phone_source, total_paise, recovery_url)
                VALUES (?, ?, '919800000000', 'contact', 1249900, ?) ON CONFLICT (token) DO NOTHING""", checkout, who, RECOVERY));
    }

    private void at(String istTime) {
        clock.setIst("2026-09-28T" + istTime + ":00");
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
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1')""", id, "tok-" + UUID.randomUUID());
            consent(c, id, "push", "marketing");
            consent(c, id, "whatsapp", "transactional");      // utility: an order-update opt-in is enough
            return null;
        });
        return id;
    }

    private static void consent(java.sql.Connection c, UUID id, String channel, String purpose) throws SQLException {
        Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), CAST(? AS purpose), 'granted', 'test', now() - interval '30 days')""", id, channel, purpose);
    }

    private void event(UUID who, String name, String props, Instant at) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'razorpay', ?)",
                who, name, props, at.atOffset(ZoneOffset.UTC)));
    }

    private String run(String subject) {
        return run(subject, PaymentFailed.INTENT);
    }

    private String run(String subject, String intent) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE intent_key = ? AND subject_key = ? ORDER BY id DESC LIMIT 1""", intent, subject);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private String attempts(String subject) {
        return db.inTx(c -> {
            var out = new StringBuilder();
            try (var ps = Sql.prepare(c, """
                    SELECT a.step_index || ':' || a.channel || ':' || a.result || ':' || COALESCE(a.reason, '')
                      FROM cascade_attempts a JOIN cascade_runs r ON r.id = a.run_id
                     WHERE r.intent_key = 'payment_failed' AND r.subject_key = ? ORDER BY a.id""", subject);
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.append(rs.getString(1)).append("; ");
            }
            return out.toString();
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
}
