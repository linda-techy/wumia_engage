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

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
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
        assertEquals("exhausted|sent", run(cart));
        assertEquals("Still in your bag: Linen Kurta", push.last.title(), "the ₹1,700 kurta, not the ₹799 dupatta");
        assertTrue(push.last.url().endsWith("/cart"), push.last.url());
        assertEquals(java.time.Duration.ofHours(12), push.last.ttl());
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
        assertEquals("exhausted|blocked:NO_MARKETING_CONSENT", run(cart));
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

    @Test void the_cart_link_prefers_the_storefront_domain() {
        assertEquals("https://www.wumika.com/cart", CartRecovery.cartUrl("https://www.wumika.com/", "x.myshopify.com"));
        assertEquals("https://x.myshopify.com/cart", CartRecovery.cartUrl("", "x.myshopify.com"));
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
