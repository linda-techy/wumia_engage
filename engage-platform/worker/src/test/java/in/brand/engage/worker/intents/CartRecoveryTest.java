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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** cart_recovery from a cart_updated event to a sends row, against Postgres. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CartRecoveryTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakePush push;
    @Inject WorkerTestBeans.FakeWhatsApp wa;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        wa.reset();
        push.last = null;
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
            // No global holdout: it would hold out 1 in 20 test identities.
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.global_pct', '*', '0', ?, 'test')""", operator(c));
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.journey_pct', '*', '0', ?, 'test')""", operator(c));
            return null;
        });
        config.invalidate();
    }

    @Test void a_cart_left_for_45_minutes_gets_one_push_about_its_most_expensive_item() {
        var id = subscriber(true);
        var cart = cart(id, 2, false);
        cartUpdated(id, cart, clock.instant().minus(Duration.ofMinutes(50)));

        dispatcher.dispatchOnce();

        var send = lastSend(cart);
        assertEquals("sent|push_cart_recovery_v1", send);
        assertEquals("waiting|", run(cart), "the email step follows in 20 h");
        assertEquals("Still in your bag: Linen Kurta", push.last.title(), "the ₹1,700 kurta, not the ₹799 dupatta");
        assertTrue(push.last.url().contains("/cart?utm_source=engage&utm_medium=push&utm_campaign=cart_recovery"),
                push.last.url());
        assertEquals(java.time.Duration.ofHours(12), push.last.ttl());
    }

    /** WhatsApp first, as most Indian D2C stores run it (owner, 2026-10-28): push is the free second nudge. */
    @Test void a_whatsapp_customer_gets_whatsapp_first_then_a_push_then_the_code_a_day_later() {
        var id = subscriber(true);
        whatsappOptIn(id);
        var cart = cart(id, 2, false);
        cartUpdated(id, cart, clock.instant().minus(Duration.ofMinutes(31)));

        dispatcher.dispatchOnce();
        assertEquals(1, wa.all.size(), "WhatsApp at 30 minutes");
        assertTrue(wa.all.getLast().templateKey().startsWith("cart_reminder"), wa.all.getLast().templateKey());
        assertNull(push.last, "no push yet");

        clock.setIst("2026-09-28T15:00:00");          // 3 h after the WhatsApp
        orchestrator.tick(100);
        assertNotNull(push.last, "the free push 3 h later");
        assertEquals("sent|push_cart_recovery_v1", lastSend(cart));

        clock.setIst("2026-09-29T11:55:00");          // just under 24 h after the WhatsApp: the one-a-day limit
        orchestrator.tick(100);
        assertEquals(1, wa.all.size(), "the offer waits beyond a day");

        clock.setIst("2026-09-29T12:40:00");          // 24½ h after the WhatsApp
        orchestrator.tick(100);
        assertEquals(2, wa.all.size(), "the code a day later");
        assertTrue(wa.all.getLast().templateKey().startsWith("cart_offer"), wa.all.getLast().templateKey());
        assertTrue(lastSend(cart).startsWith("sent|cart_offer"), "not held by the daily WhatsApp limit: " + lastSend(cart));
    }

    /** Free shipping from ₹600 (the theme's FREE_SHIPPING_THRESHOLD): the push says how far a small bag is from it. */
    @Test void a_bag_under_600_hears_how_much_more_gets_free_shipping() {
        var id = subscriber(true);
        var small = cart(id, 1, false);
        db.inTx(c -> Sql.update(c, "UPDATE carts SET total_paise = 45000 WHERE cart_token = ?", small));
        cartUpdated(id, small, clock.instant().minus(Duration.ofMinutes(50)));
        dispatcher.dispatchOnce();
        assertEquals("It's saved for you. Add ₹150 more for FREE shipping.", push.last.body());

        var id2 = subscriber(true);
        var big = cart(id2, 2, false);
        cartUpdated(id2, big, clock.instant().minus(Duration.ofMinutes(50)));
        dispatcher.dispatchOnce();
        assertEquals("It's saved for you. Complete your order when you're ready.", push.last.body(), "₹2,499: already free");
    }

    @Test void a_click_on_the_push_marks_the_cascade_succeeded() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant().minus(Duration.ofMinutes(50)));
        dispatcher.dispatchOnce();
        assertEquals("waiting|", run(cart), "the email step follows in 20 h");

        // As ingest-api writes it when the service worker's signed click beacon arrives.
        long runId = count("SELECT id FROM cascade_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1", cart);
        event(id, "push_clicked", "{\"send_id\":1,\"cascade_run_id\":" + runId + "}", clock.instant());
        dispatcher.dispatchOnce();

        assertEquals("succeeded|clicked", run(cart));
    }

    @Test void a_whatsapp_reply_or_button_tap_succeeds_but_a_read_does_not() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant().minus(Duration.ofMinutes(50)));
        dispatcher.dispatchOnce();
        // Stand the push send in for a WhatsApp one, as Meta's wamid would identify it.
        long sendId = count("SELECT s.id FROM sends s JOIN cascade_runs r ON r.id = s.cascade_run_id WHERE r.subject_key = ?", cart);
        var wamid = "wamid.test-" + UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "UPDATE sends SET channel = 'whatsapp', provider_id = ? WHERE id = ?", wamid, sendId));

        event(id, "wa_read", "{\"send_id\":" + sendId + "}", clock.instant());
        dispatcher.dispatchOnce();
        assertEquals("waiting|", run(cart), "a read is not engagement for a promotional cascade");

        event(id, "fit_ok", "{\"message_id\":\"m1\",\"context_wamid\":\"" + wamid + "\"}", clock.instant());
        dispatcher.dispatchOnce();
        assertEquals("succeeded|replied", run(cart));
    }

    @Test void a_recent_change_waits_45_minutes_and_the_tick_sends_it() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant());

        dispatcher.dispatchOnce();
        assertEquals("active|", run(cart));
        assertNull(lastSend(cart));

        clock.setIst("2026-09-28T12:46:00");
        orchestrator.tick(100);

        assertEquals("sent|push_cart_recovery_v1", lastSend(cart));
    }

    @Test void without_consent_the_attempt_is_recorded_as_blocked_with_the_reason() {
        var id = subscriber(false);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant().minus(Duration.ofHours(1)));

        dispatcher.dispatchOnce();

        assertEquals("blocked|push_cart_recovery_v1", lastSend(cart));
        assertEquals("exhausted|skipped:HAS_EMAIL_MARKETING", run(cart), "push blocked, then no email consent either");
    }

    @Test void every_cart_change_restarts_the_cascade_with_one_live_run() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant());
        dispatcher.dispatchOnce();
        cartUpdated(id, cart, clock.instant().plus(Duration.ofMinutes(10)));
        dispatcher.dispatchOnce();

        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ? AND status IN ('active','waiting')", cart));
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ? AND outcome = 'cart_changed'", cart));
    }

    @Test void the_order_for_the_cart_ends_the_cascade_before_it_sends() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant());
        dispatcher.dispatchOnce();

        event(id, "order_placed", "{\"cart_token\":\"" + cart + "\",\"order_id\":\"9001\"}", clock.instant());
        dispatcher.dispatchOnce();
        clock.setIst("2026-09-28T13:00:00");
        orchestrator.tick(100);

        assertEquals("cancelled|order_placed", run(cart));
        assertNull(lastSend(cart));
    }

    @Test void an_emptied_cart_ends_the_cascade_and_starts_nothing() {
        var id = subscriber(true);
        var cart = cart(id, 1, false);
        cartUpdated(id, cart, clock.instant());
        dispatcher.dispatchOnce();

        db.inTx(c -> Sql.update(c, "UPDATE carts SET item_count = 0, lines = '[]' WHERE cart_token = ?", cart));
        cartUpdated(id, cart, clock.instant().plus(Duration.ofMinutes(5)));
        dispatcher.dispatchOnce();

        assertEquals("cancelled|cart_emptied", run(cart));
        assertEquals(0L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ? AND status IN ('active','waiting')", cart));
    }

    @Test void an_anonymous_cart_starts_nothing() {
        var cart = cart(null, 1, false);
        cartUpdated(null, cart, clock.instant().minus(Duration.ofHours(1)));

        dispatcher.dispatchOnce();

        assertEquals(0L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ?", cart));
    }

    @Test void storefront_links_prefer_the_storefront_domain() {
        assertEquals("https://www.wumika.com/cart", new Storefront("https://www.wumika.com/", "x.myshopify.com").cart());
        assertEquals("https://x.myshopify.com/products/linen-kurta?variant=44",
                new Storefront("", "x.myshopify.com").product("linen-kurta", "44"));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private UUID subscriber(boolean consent) {
        var id = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1')""", id, "tok-" + UUID.randomUUID());
            if (consent) {
                Sql.update(c, """
                        INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                        VALUES (?, 'push', 'marketing', 'granted', 'test', now() - interval '30 days')""", id);
            }
            return null;
        });
        return id;
    }

    /** A WhatsApp marketing opt-in and number; the reminder and offer approved by Meta; a cart code set. */
    private void whatsappOptIn(UUID id) {
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)",
                    id, "919" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'whatsapp', 'marketing', 'granted', 'test', now() - interval '30 days')""", id);
            for (var t : java.util.List.of("cart_reminder_v1", "cart_reminder_v2", "cart_offer_v1", "cart_offer_v2")) {
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                        VALUES (?, 'en', ?, 'marketing', 'marketing', 'APPROVED', 'GREEN')
                        ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""", t, t);
            }
            for (var kv : java.util.List.of(new String[] {"journey.abandon.discount_code", "\"BAG10\""},
                    new String[] {"journey.abandon.discount_label", "\"10% off\""})) {
                Sql.update(c, "INSERT INTO config_versions (key, selector, value, changed_by, reason) VALUES (?, '*', CAST(? AS jsonb), ?, 'test')",
                        kv[0], kv[1], operator(c));
            }
            return null;
        });
        config.invalidate();
    }

    private String cart(UUID identity, int items, boolean converted) {
        var token = "test-cart-" + UUID.randomUUID();
        db.inTx(c -> Sql.update(c, """
                INSERT INTO carts (cart_token, identity_id, item_count, total_paise, lines, converted_at)
                VALUES (?, ?, ?, 249900,
                        '[{"variant_id":"1","title":"Cotton Dupatta","size":"Default Title","qty":1,"price_paise":79900},
                          {"variant_id":"2","title":"Linen Kurta","size":"M","qty":1,"price_paise":170000}]'::jsonb,
                        CASE WHEN ? THEN now() END)""", token, identity, items, converted));
        return token;
    }

    private void cartUpdated(UUID identity, String cart, Instant at) {
        event(identity, "cart_updated", "{\"cart_token\":\"" + cart + "\",\"item_count\":1}", at);
    }

    private void event(UUID identity, String name, String props, Instant at) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'shopify', ?)",
                identity, name, props, at.atOffset(ZoneOffset.UTC)));
    }

    /** status|template of the newest sends row for the cart's live or last run. */
    private String lastSend(String cart) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT s.status || '|' || s.template_key FROM sends s JOIN cascade_runs r ON r.id = s.cascade_run_id
                     WHERE r.subject_key = ? ORDER BY s.id DESC LIMIT 1""", cart);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private String run(String cart) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE subject_key = ? ORDER BY id DESC LIMIT 1""", cart);
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
