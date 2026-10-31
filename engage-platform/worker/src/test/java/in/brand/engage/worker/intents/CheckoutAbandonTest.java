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

/** P5-T03/T04: abandoned checkout and cart, WhatsApp first, push as fallback, against Postgres. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CheckoutAbandonTest {

    static final String RECOVERY = "https://wumika-dev.myshopify.com/checkouts/cn/hWN4abc/recover?key=k123";

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject WorkerTestBeans.FakePush push;
    @Inject WorkerTestBeans.FakeDiscounts discounts;
    @Inject in.brand.engage.policy.Holdouts holdouts;
    @Inject in.brand.engage.persistence.EventWriter events;

    String cart;
    String checkout;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
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
            // Other tests book marketing WhatsApp spend on this same test date; 0 = no daily budget.
            setConfig(c, "budget.whatsapp.marketing.daily_paise", "0");
            for (var t : List.of("checkout_reminder_v1", "checkout_offer_v1", "cart_reminder_v1")) {
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                        VALUES (?, 'en', ?, 'marketing', 'marketing', 'APPROVED', 'GREEN')
                        ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""", t, t);
            }
            return null;
        });
        config.invalidate();
        cart = "test-cart-" + UUID.randomUUID();
        checkout = "test-checkout-" + UUID.randomUUID();
    }

    @Test void whatsapp_at_30_minutes_then_push_at_4_hours_and_no_offer_without_a_code() {
        var who = customer(true, true);
        abandon(who, 1249900);

        at("12:29");
        assertTrue(wa.all.isEmpty(), "nothing before the stall time");
        at("12:31");
        assertFalse(wa.all.isEmpty(), attempts(checkout));
        var call = wa.all.getLast().whatsapp();
        assertEquals("checkout_reminder_v1", call.name());
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings"), call.bodyParams());
        assertEquals(List.of("checkouts/cn/hWN4abc/recover?key=k123"), call.urlButtonSuffixes(), "Shopify's saved checkout, on our domain");
        assertTrue(push.all.isEmpty());

        at("16:32");
        assertEquals("Your checkout is saved", push.all.getLast().title());
        assertTrue(push.all.getLast().url().contains("/checkouts/cn/hWN4abc/recover?key=k123"), push.all.getLast().url());

        clock.setIst("2026-09-29T12:40:00");
        orchestrator.tick(200);
        assertEquals(1, wa.all.size(), "no discount message without a code");
        assertTrue(attempts(checkout).contains("guard:HAS_DISCOUNT"), attempts(checkout));
        assertEquals("exhausted|skipped:HAS_EMAIL_MARKETING", run(checkout), "then the email step, without email consent");
    }

    @Test void with_a_code_the_last_whatsapp_carries_it() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_code", "\"WUMIKA10\"");
            setConfig(c, "journey.abandon.discount_label", "\"10% off\"");
            return null;
        });
        config.invalidate();
        var who = customer(true, true);
        abandon(who, 1249900);
        at("12:31");
        at("16:32");
        clock.setIst("2026-09-29T12:40:00");
        orchestrator.tick(200);

        assertEquals(2, wa.all.size(), attempts(checkout));
        var offer = wa.all.getLast().whatsapp();
        assertEquals("checkout_offer_v1", offer.name());
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings", "WUMIKA10", "10% off"), offer.bodyParams());
        assertEquals("waiting|", run(checkout), "the email step follows in 12 h");
    }

    /** No push (ambili, 2026-10-29): the skipped push must not pull the code forward into the one-a-day limit. */
    @Test void without_push_the_code_still_waits_a_day_after_the_first_whatsapp_and_goes() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_code", "\"WUMIKA10\"");
            setConfig(c, "journey.abandon.discount_label", "\"10% off\"");
            return null;
        });
        config.invalidate();
        var who = customer(true, false);
        abandon(who, 1249900);
        at("12:31");
        assertEquals(1, wa.all.size(), "the reminder at 30 min");
        at("16:32");                                   // the push step: no push consent, skipped
        assertEquals(1, wa.all.size(), "the code waits: " + attempts(checkout));
        assertFalse(attempts(checkout).contains("FREQUENCY_CAP"), attempts(checkout));
        clock.setIst("2026-09-29T12:20:00");           // 23¾ h after the reminder
        orchestrator.tick(200);
        assertEquals(1, wa.all.size(), "still under a day");
        clock.setIst("2026-09-29T12:40:00");           // just past a day after the reminder
        orchestrator.tick(200);
        assertEquals(2, wa.all.size(), attempts(checkout));
        assertEquals("checkout_offer_v1", wa.all.getLast().whatsapp().name());
    }

    @Test void without_push_the_cart_code_waits_a_day_after_the_reminder_and_goes() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_code", "\"WUMIKA10\"");
            setConfig(c, "journey.abandon.discount_label", "\"10% off\"");
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('cart_offer_v1', 'en', 'cart_offer_v1', 'marketing', 'marketing', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
            return null;
        });
        config.invalidate();
        var who = customer(true, false);
        cartRow(who, 1249900);
        event(who, "cart_updated", "{\"cart_token\":\"" + cart + "\",\"item_count\":1}");
        dispatcher.dispatchOnce();
        at("12:31");
        assertEquals("cart_reminder_v1", wa.all.getLast().whatsapp().name());
        at("15:32");                                   // the push step: no push consent, skipped
        assertEquals(1, wa.all.size(), "the code waits: " + attempts(cart));
        clock.setIst("2026-09-29T13:05:00");
        orchestrator.tick(200);
        assertEquals("cart_offer_v1", wa.all.getLast().whatsapp().name(), attempts(cart));
        assertFalse(attempts(cart).contains("FREQUENCY_CAP"), attempts(cart));
    }

    @Test void unique_mode_makes_a_single_use_code_when_the_discount_message_goes() {
        uniqueCodes();
        var who = customer(true, true);
        abandon(who, 1249900);
        at("12:31");
        at("16:32");
        assertTrue(discounts.created.isEmpty(), "no code until the discount message is due");
        clock.setIst("2026-09-29T12:40:00");
        orchestrator.tick(200);

        assertEquals(1, discounts.created.size(), attempts(checkout));
        var spec = discounts.created.getFirst();
        assertTrue(spec.code().matches("WUM[A-HJ-NP-Z2-9]{8}"), spec.code());
        assertEquals(15, spec.percent());
        assertEquals(java.time.Duration.ofHours(48), java.time.Duration.between(spec.startsAt(), spec.endsAt()));
        var offer = wa.all.getLast().whatsapp();
        assertEquals("checkout_offer_v1", offer.name());
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings", spec.code(), "15% off"), offer.bodyParams());
        assertEquals(spec.code(), runVar(checkout, "code"), "kept on the run, so a retry reuses it");
    }

    @Test void if_shopify_refuses_the_code_the_discount_message_is_skipped() {
        uniqueCodes();
        discounts.refuse = new in.brand.engage.worker.intents.DiscountCodes.Failed("access denied: the app needs write_discounts", false);
        var who = customer(true, true);
        abandon(who, 1249900);
        at("12:31");
        at("16:32");
        clock.setIst("2026-09-29T12:40:00");
        orchestrator.tick(200);

        assertEquals(1, wa.all.size(), "the reminder went; no message with a code that does not exist");
        assertTrue(attempts(checkout).contains("vars:journey_discount"), attempts(checkout));
        assertEquals("exhausted|skipped:HAS_EMAIL_MARKETING", run(checkout));
        assertTrue(attempts(checkout).contains("write_discounts"), attempts(checkout));
    }

    @Test void the_discount_goes_once_per_customer_in_60_days() {
        discountCode();
        var who = customer(true, true);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, created_at)
                VALUES (?, 'whatsapp', 'marketing', 'checkout_offer_v1', ?, 'delivered', now() - interval '20 days')""",
                who, "test-offer-" + UUID.randomUUID()));
        abandon(who, 1249900);
        assertFalse(hasCode(checkout), "got the discount 20 days ago: reminders only");
    }

    @Test void two_open_checkouts_carry_the_discount_once() {
        discountCode();
        var who = customer(true, true);
        abandon(who, 1249900);
        assertTrue(hasCode(checkout));
        checkout = "test-checkout-" + UUID.randomUUID();
        cart = "test-cart-" + UUID.randomUUID();
        abandon(who, 1249900);
        assertFalse(hasCode(checkout), "the first checkout's run already holds the offer");
    }

    @Test void first_time_buyers_only_skips_customers_with_an_order() {
        discountCode();
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_new_customers_only", "true");
            return null;
        });
        config.invalidate();
        var buyer = customer(true, true);
        db.inTx(c -> Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, '#1001', ?, 99900, now())",
                "test-order-" + UUID.randomUUID(), buyer));
        abandon(buyer, 1249900);
        assertFalse(hasCode(checkout), "has ordered before");

        var newcomer = customer(true, true);
        checkout = "test-checkout-" + UUID.randomUUID();
        cart = "test-cart-" + UUID.randomUUID();
        abandon(newcomer, 1249900);
        assertTrue(hasCode(checkout), "never ordered: gets the code");
    }

    @Test void without_a_whatsapp_opt_in_the_push_goes_at_30_minutes() {
        var who = customer(false, true);
        abandon(who, 1249900);
        at("12:31");
        assertTrue(wa.all.isEmpty());
        assertEquals(1, push.all.size(), "the free fallback, at once");
    }

    @Test void below_the_whatsapp_minimum_only_push_goes() {
        db.inTx(c -> {
            setConfig(c, "journey.checkout_abandon.wa_min_cart_paise", "2000000");     // ₹20,000
            return null;
        });
        config.invalidate();
        var who = customer(true, true);
        abandon(who, 1249900);
        at("12:31");
        assertTrue(wa.all.isEmpty(), "₹12,499 is below the ₹20,000 WhatsApp minimum");
        assertEquals(1, push.all.size(), "push instead, at once");
    }

    @Test void a_later_checkout_update_restarts_the_wait_and_an_order_ends_it() {
        var who = customer(true, true);
        abandon(who, 1249900);
        clock.setIst("2026-09-28T12:20:00");
        event(who, "checkout_updated", "{\"checkout_token\":\"" + checkout + "\"}");
        dispatcher.dispatchOnce();
        at("12:31");
        assertTrue(wa.all.isEmpty(), "still in checkout 20 min in: the wait restarted");
        at("12:51");
        assertEquals(1, wa.all.size());

        event(who, "order_placed", "{\"checkout_token\":\"" + checkout + "\",\"cart_token\":\"" + cart + "\"}");
        dispatcher.dispatchOnce();
        assertEquals("cancelled|order_placed", run(checkout));
    }

    @Test void a_completed_checkout_ends_the_run() {
        var who = customer(true, true);
        abandon(who, 1249900);
        db.inTx(c -> Sql.update(c, "UPDATE checkouts SET completed_at = now() WHERE token = ?", checkout));
        event(who, "checkout_updated", "{\"checkout_token\":\"" + checkout + "\"}");
        dispatcher.dispatchOnce();
        assertEquals("cancelled|checkout_completed", run(checkout));
    }

    @Test void starting_checkout_ends_cart_recovery_for_that_cart() {
        var who = customer(true, true);
        cartRow(who, 1249900);
        event(who, "cart_updated", "{\"cart_token\":\"" + cart + "\",\"item_count\":1}");
        dispatcher.dispatchOnce();
        assertEquals("active|", run(cart));

        abandon(who, 1249900);
        assertEquals("cancelled|checkout_started", run(cart));
    }

    @Test void cart_recovery_whatsapps_first_then_pushes_those_who_did_not_tap() {
        // WhatsApp first, as most Indian D2C stores run it (owner, 2026-10-28): WhatsApp at 30 min, push 3 h later.
        var who = customer(true, true);
        cartRow(who, 1249900);
        event(who, "cart_updated", "{\"cart_token\":\"" + cart + "\",\"item_count\":1}");
        dispatcher.dispatchOnce();
        at("12:31");
        var call = wa.all.getLast().whatsapp();
        assertEquals("cart_reminder_v1", call.name(), "the photo version is not approved in this test");
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings"), call.bodyParams());
        assertTrue(push.all.stream().noneMatch(m -> m.templateKey().equals("push_cart_recovery_v1")), "the push waits 3 h");
        at("15:32");
        assertEquals("push_cart_recovery_v1", push.all.getLast().templateKey(), attempts(cart));
    }

    @Test void a_day_later_the_cart_coupon_opens_the_bag_with_the_code_applied() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_code", "\"WUMIKA10\"");
            setConfig(c, "journey.abandon.discount_label", "\"10% off\"");
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('cart_offer_v1', 'en', 'cart_offer_v1', 'marketing', 'marketing', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
            return null;
        });
        config.invalidate();
        var who = customer(true, true);
        cartRow(who, 1249900);
        event(who, "cart_updated", "{\"cart_token\":\"" + cart + "\",\"item_count\":1}");
        dispatcher.dispatchOnce();
        at("12:31");                                   // WhatsApp reminder
        at("15:32");                                   // push
        clock.setIst("2026-09-29T13:05:00");           // 24½ h after the reminder
        orchestrator.tick(200);
        var call = wa.all.getLast().whatsapp();
        assertEquals("cart_offer_v1", call.name(), attempts(cart));
        assertEquals(List.of("Priya", "Kundan Jhumka Earrings", "WUMIKA10", "10% off"), call.bodyParams());
        assertEquals(List.of("discount/WUMIKA10?redirect=%2Fcart"), call.urlButtonSuffixes());
    }

    /** Held out of the journey (owner, 2026-10-31): the holdout goes off, the journey starts again, once. */
    @Test void a_checkout_held_out_is_reminded_once_the_holdout_is_off() {
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.journey_pct', 'checkout_abandon', '50'::jsonb, ?, 'test')""", operator(c));
            return null;
        });
        config.invalidate();
        UUID who = null;
        for (int i = 0; i < 40 && who == null; i++) {               // someone the 50% puts in the control group
            var candidate = customer(true, false);
            boolean control = db.inTx(c -> holdouts.bucket(c, candidate, "checkout_abandon", new java.math.BigDecimal("50"))
                    == in.brand.engage.policy.Holdouts.Bucket.CONTROL);
            if (control) who = candidate;
        }
        assertNotNull(who, "no one in control in 40 tries at 50%");
        abandon(who, 1249900);
        at("12:31");
        at("16:32");
        clock.setIst("2026-09-29T12:40:00");
        orchestrator.tick(200);
        assertTrue(wa.all.isEmpty(), attempts(checkout));
        assertTrue(attempts(checkout).contains("HOLDOUT_CONTROL"), attempts(checkout));
        assertTrue(run(checkout).startsWith("exhausted"), run(checkout));

        var still = db.inTx(c -> in.brand.engage.worker.HeldRerun.rerun(c, events, config.snapshot(), true));
        assertTrue(still.started().stream().noneMatch(h -> h.subject().equals(checkout)), "not while the holdout is on");

        db.inTx(c -> Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                VALUES ('holdout.journey_pct', 'checkout_abandon', '0'::jsonb, ?, 'test')""", operator(c)));
        config.invalidate();
        var r = db.inTx(c -> in.brand.engage.worker.HeldRerun.rerun(c, events, config.snapshot(), true));
        assertEquals(1, r.started().stream().filter(h -> h.subject().equals(checkout)).count());
        assertEquals(0, db.inTx(c -> in.brand.engage.worker.HeldRerun.rerun(c, events, config.snapshot(), true))
                .started().stream().filter(h -> h.subject().equals(checkout)).count(), "a second pass starts nothing");

        // The fresh event is stamped now (the database's clock); the journey runs the usual way from it.
        db.inTx(c -> Sql.update(c, "UPDATE events SET occurred_at = ? WHERE dedupe_key LIKE 'rerun_held:%' AND dispatched_at IS NULL",
                clock.instant().atOffset(ZoneOffset.UTC)));
        dispatcher.dispatchOnce();
        clock.setIst("2026-09-29T13:15:00");
        orchestrator.tick(200);
        assertEquals("checkout_reminder_v1", wa.all.getLast().whatsapp().name(), attempts(checkout));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void uniqueCodes() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_mode", "\"unique\"");
            setConfig(c, "journey.abandon.discount_percent", "15");
            return null;
        });
        config.invalidate();
    }

    private String runVar(String subject, String key) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT vars->>? FROM cascade_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1", key, subject);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private void discountCode() {
        db.inTx(c -> {
            setConfig(c, "journey.abandon.discount_code", "\"WUMIKA10\"");
            setConfig(c, "journey.abandon.discount_label", "\"10% off\"");
            return null;
        });
        config.invalidate();
    }

    private boolean hasCode(String subject) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT vars->>'discount' IS NOT NULL FROM cascade_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1""", subject);
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no run for " + subject);
                return rs.getBoolean(1);
            }
        });
    }

    private void at(String istTime) {
        clock.setIst("2026-09-28T" + istTime + ":00");
        orchestrator.tick(200);
    }

    private void abandon(UUID who, long totalPaise) {
        if (count("SELECT count(*) FROM carts WHERE cart_token = ?", cart) == 0) cartRow(who, totalPaise);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO checkouts (token, cart_token, identity_id, phone, phone_source, total_paise, recovery_url)
                VALUES (?, ?, ?, '919800000000', 'contact', ?, ?)""", checkout, cart, who, totalPaise, RECOVERY));
        event(who, "checkout_updated", "{\"checkout_token\":\"" + checkout + "\",\"has_phone\":true}");
        dispatcher.dispatchOnce();
    }

    private void cartRow(UUID who, long totalPaise) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO carts (cart_token, identity_id, item_count, total_paise, lines)
                VALUES (?, ?, 2, ?, '[{"variant_id":"1","title":"Pearl Stud Earrings","qty":1,"price_paise":79900},
                                     {"variant_id":"2","title":"Kundan Jhumka Earrings","qty":1,"price_paise":1170000}]'::jsonb)""",
                cart, who, totalPaise));
    }

    private UUID customer(boolean waMarketing, boolean pushMarketing) {
        var id = UUID.randomUUID();
        var phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1')""", id, "tok-" + UUID.randomUUID());
            if (pushMarketing) consent(c, id, "push");
            if (waMarketing) consent(c, id, "whatsapp");
            return null;
        });
        return id;
    }

    private static void consent(java.sql.Connection c, UUID id, String channel) throws SQLException {
        Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), 'marketing', 'granted', 'test', now() - interval '30 days')""", id, channel);
    }

    private static void setConfig(java.sql.Connection c, String key, String jsonValue) throws SQLException {
        Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                VALUES (?, '*', ?::jsonb, ?, 'test')""", key, jsonValue, operator(c));
    }

    private void event(UUID who, String name, String props) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'shopify', ?)",
                who, name, props, clock.instant().atOffset(ZoneOffset.UTC)));
    }

    private String run(String subject) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1""",
                    subject); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private String attempts(String subject) {
        return db.inTx(c -> {
            var out = new StringBuilder();
            try (var ps = Sql.prepare(c, """
                    SELECT a.step_index || ':' || a.channel || ':' || a.result || ':' || COALESCE(a.reason, '')
                           || ' (' || COALESCE(s.failed_reason, s.status::text, '') || ')'
                      FROM cascade_attempts a JOIN cascade_runs r ON r.id = a.run_id LEFT JOIN sends s ON s.id = a.send_id
                     WHERE r.subject_key = ? ORDER BY a.id""", subject); var rs = ps.executeQuery()) {
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
