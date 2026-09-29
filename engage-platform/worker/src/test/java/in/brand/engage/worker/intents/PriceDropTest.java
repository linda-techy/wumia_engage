package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

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

/** price_drop from a price_dropped event to sends rows, against Postgres. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PriceDropTest {

    @Inject EventDispatcher dispatcher;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakePush push;

    String variant;

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
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.global_pct', '*', '0', ?, 'test')""", operator(c));
            return null;
        });
        config.invalidate();
        push.all.clear();
        variant = "v" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test void a_drop_reaches_the_carts_and_the_waitlist_holding_the_variant_with_real_numbers() {
        cart(subscriber(), variant, null);
        waitlist(subscriber(), variant);

        dropped(variant, 189900, 149900);

        assertEquals(2, push.all.size());
        var msg = push.all.getFirst();
        assertEquals("push_price_drop_v1", msg.templateKey());
        assertEquals("Price dropped: Linen Kurta", msg.title());
        assertTrue(msg.body().startsWith("Now ₹1,499, was ₹1,899."), msg.body());
        assertTrue(msg.url().contains("/products/linen-kurta?variant=" + variant + "&utm_source=engage"), msg.url());
        assertEquals(Duration.ofHours(24), msg.ttl());
        assertFalse(msg.highUrgency());
    }

    @Test void a_shopper_with_it_in_the_cart_and_on_the_waitlist_gets_one_push() {
        var priya = subscriber();
        cart(priya, variant, null);
        waitlist(priya, variant);

        dropped(variant, 189900, 149900);

        assertEquals(1, push.all.size());
    }

    @Test void converted_anonymous_and_unrelated_carts_are_not_reached() {
        cart(subscriber(), variant, Instant.now());                    // already bought
        cart(null, variant, null);                                     // nobody to reach
        cart(subscriber(), "other-" + variant, null);                  // a different item

        dropped(variant, 189900, 149900);

        assertEquals(0, push.all.size());
        assertEquals(0L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'price_drop' AND subject_key LIKE ?", variant + ":%"));
    }

    @Test void the_waitlist_is_read_not_consumed_so_the_restock_alert_still_comes() {
        var priya = subscriber();
        waitlist(priya, variant);

        dropped(variant, 189900, 149900);

        assertEquals(1, push.all.size());
        assertEquals(1L, count("SELECT count(*) FROM stock_waitlist WHERE variant_id = ? AND notified_at IS NULL", variant));
    }

    @Test void a_second_drop_the_same_day_is_held_back_by_the_one_day_cooldown() {
        var priya = subscriber();
        var second = "w" + variant;
        cart(priya, variant, null);
        cart(priya, second, null);

        dropped(variant, 189900, 149900);
        dropped(second, 99900, 79900);

        assertEquals(1, push.all.size(), "a store-wide markdown is one push a day, not one per item");
    }

    @Test void an_event_that_is_not_a_fall_starts_nothing() {
        cart(subscriber(), variant, null);

        dropped(variant, 149900, 189900);
        dropped(variant, 149900, 149900);

        assertEquals(0, push.all.size());
    }

    @Test void rupees_are_written_the_indian_way() {
        assertEquals("₹1,499", PriceDrop.rupees(149900));
        assertEquals("₹12,499.50", PriceDrop.rupees(1249950));
        assertEquals("₹1,49,999", PriceDrop.rupees(14999900));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void dropped(String variantId, long oldPaise, long newPaise) {
        var props = """
                {"variant_id":"%s","product_id":"88","product_title":"Linen Kurta","product_handle":"linen-kurta",
                 "variant_title":"M","old_price_paise":%d,"new_price_paise":%d}""".formatted(variantId, oldPaise, newPaise);
        db.inTx(c -> Sql.update(c, "INSERT INTO events (name, props, source) VALUES ('price_dropped', ?::jsonb, 'shopify')", props));
        dispatcher.dispatchOnce();
    }

    private UUID identity() {
        var id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
        return id;
    }

    /** A shopper with one fresh web push device and a push grant. */
    private UUID subscriber() {
        var id = identity();
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver,
                                         last_refreshed_at)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'notify_me', 'push_v1', ?)""",
                    id, "tok-" + UUID.randomUUID(), Instant.now().atOffset(ZoneOffset.UTC));
            return Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'push', 'marketing', 'granted', 'test', now() - interval '60 days')""", id);
        });
        return id;
    }

    private void cart(UUID identity, String variantId, Instant convertedAt) {
        var lines = """
                [{"variant_id":"%s","title":"Linen Kurta","size":"M","qty":1,"price_paise":189900}]""".formatted(variantId);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO carts (cart_token, identity_id, item_count, total_paise, lines, converted_at)
                VALUES (?, ?, 1, 189900, ?::jsonb, ?)""",
                "cart-" + UUID.randomUUID(), identity, lines,
                convertedAt == null ? null : convertedAt.atOffset(ZoneOffset.UTC)));
    }

    private void waitlist(UUID identity, String variantId) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO stock_waitlist (identity_id, variant_id, product_handle, size_label, created_at)
                VALUES (?, ?, 'linen-kurta', 'M', now() - interval '1 day')""", identity, variantId));
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
