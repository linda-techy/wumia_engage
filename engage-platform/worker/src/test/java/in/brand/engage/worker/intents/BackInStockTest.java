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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** back_in_stock from a variant_restocked event to sends rows, against Postgres. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class BackInStockTest {

    @Inject EventDispatcher dispatcher;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakePush push;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject in.brand.engage.orchestrator.DefaultOrchestrator orchestrator;

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
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('budget.whatsapp.marketing.daily_paise', '*', '0', ?, 'test')""", operator(c));
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('back_in_stock_v1', 'en', 'back_in_stock_v1', 'marketing', 'marketing', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
            return null;
        });
        config.invalidate();
        push.all.clear();
        wa.reset();
        variant = "v" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test void a_restock_alerts_each_waitlisted_shopper_with_their_size_and_a_link_to_the_variant() {
        var priya = subscriber(Instant.now());
        var asha = subscriber(Instant.now());
        waitlist(priya, "M", clock.instant().minus(Duration.ofDays(3)));
        waitlist(asha, null, clock.instant().minus(Duration.ofDays(2)));

        restocked(1, "Linen Kurta", "linen-kurta", "L / Indigo");

        assertEquals(2, push.all.size());
        var titles = push.all.stream().map(m -> m.title()).sorted().toList();
        assertEquals(List.of("Size L / Indigo is back: Linen Kurta", "Size M is back: Linen Kurta"), titles,
                "the size the shopper picked, else Shopify's variant title");
        var msg = push.all.getFirst();
        assertTrue(msg.url().contains("/products/linen-kurta?variant=" + variant + "&utm_source=engage"), msg.url());
        assertEquals(Duration.ofHours(1), msg.ttl());
        assertTrue(msg.highUrgency());
        assertEquals(0L, count("SELECT count(*) FROM stock_waitlist WHERE variant_id = ? AND notified_at IS NULL", variant));
    }

    @Test void one_restocked_unit_reaches_at_most_twenty_shoppers_oldest_first() {
        var start = clock.instant().minus(Duration.ofDays(30));
        UUID newest = null;
        for (int i = 0; i < 25; i++) {
            newest = identity();
            waitlist(newest, "M", start.plus(Duration.ofHours(i)));
        }

        restocked(1, "Linen Kurta", "linen-kurta", "M");

        assertEquals(20L, count("SELECT count(*) FROM stock_waitlist WHERE variant_id = ? AND notified_at IS NOT NULL", variant));
        assertEquals(20L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'back_in_stock' AND subject_key LIKE ?", variant + ":%"));
        assertEquals(0L, count("SELECT count(*) FROM stock_waitlist WHERE identity_id = ? AND notified_at IS NOT NULL", newest),
                "the 5 most recent sign-ups wait for the next restock");
    }

    @Test void a_one_size_product_gets_the_alert_without_a_size() {
        waitlist(subscriber(Instant.now()), null, clock.instant().minus(Duration.ofDays(1)));

        restocked(2, "Silk Dupatta", "silk-dupatta", "Default Title");

        assertEquals("Back in stock: Silk Dupatta", push.all.getFirst().title());
        assertEquals("push_back_in_stock_nosize_v1", push.all.getFirst().templateKey());
    }

    @Test void a_stale_token_still_gets_it_because_the_shopper_asked() {
        waitlist(subscriber(clock.instant().minus(Duration.ofDays(45))), "M", clock.instant().minus(Duration.ofDays(1)));

        restocked(1, "Linen Kurta", "linen-kurta", "M");

        assertEquals(1, push.all.size());
    }

    @Test void a_second_restock_does_not_alert_the_same_shoppers_again() {
        waitlist(subscriber(Instant.now()), "M", clock.instant().minus(Duration.ofDays(1)));
        restocked(1, "Linen Kurta", "linen-kurta", "M");

        restocked(3, "Linen Kurta", "linen-kurta", "M");

        assertEquals(1, push.all.size());
    }

    @Test void without_a_push_click_whatsapp_follows_15_minutes_later() {
        var priya = subscriber(Instant.now());
        whatsapp(priya);
        waitlist(priya, "M", clock.instant().minus(Duration.ofDays(3)));
        restocked(1, "Linen Kurta", "linen-kurta", "M");
        assertEquals(1, push.all.size());
        assertTrue(wa.all.isEmpty());

        tickAt(clock.instant().plus(Duration.ofMinutes(16)));
        var call = wa.all.getLast().whatsapp();
        assertEquals("back_in_stock_v1", call.name());
        assertEquals(List.of("Linen Kurta"), call.bodyParams());
        assertEquals(List.of("products/linen-kurta?variant=" + variant), call.urlButtonSuffixes());
    }

    @Test void a_push_click_means_no_whatsapp() {
        var priya = subscriber(Instant.now());
        whatsapp(priya);
        waitlist(priya, "M", clock.instant().minus(Duration.ofDays(3)));
        restocked(1, "Linen Kurta", "linen-kurta", "M");
        long runId = count("SELECT id FROM cascade_runs WHERE intent_key = 'back_in_stock' AND subject_key = ?", variant + ":" + priya);
        db.inTx(c -> orchestrator.onSignal(c, runId, "clicked"));

        tickAt(clock.instant().plus(Duration.ofMinutes(16)));
        assertTrue(wa.all.isEmpty());
    }

    @Test void without_a_push_device_whatsapp_goes_at_once() {
        var asha = identity();
        whatsapp(asha);
        waitlist(asha, "M", clock.instant().minus(Duration.ofDays(3)));
        restocked(1, "Linen Kurta", "linen-kurta", "M");
        assertTrue(push.all.isEmpty());
        assertEquals(1, wa.all.size());
    }

    @Test void selling_out_again_ends_the_pending_whatsapp() {
        var priya = subscriber(Instant.now());
        whatsapp(priya);
        waitlist(priya, "M", clock.instant().minus(Duration.ofDays(3)));
        restocked(1, "Linen Kurta", "linen-kurta", "M");

        var props = "{\"variant_id\":\"%s\",\"quantity\":0}".formatted(variant);
        db.inTx(c -> Sql.update(c, "INSERT INTO events (name, props, source) VALUES ('variant_sold_out', ?::jsonb, 'shopify')", props));
        dispatcher.dispatchOnce();
        tickAt(clock.instant().plus(Duration.ofMinutes(16)));

        assertTrue(wa.all.isEmpty(), "gone again: no message about it");
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'back_in_stock' AND subject_key = ? "
                + "AND status = 'cancelled' AND outcome = 'variant_sold_out'", variant + ":" + priya));
    }

    @Test void without_a_product_title_the_handle_names_the_item() {
        assertEquals("Floral Anarkali Kurta", BackInStock.fromHandle("floral-anarkali-kurta"));
        assertNull(BackInStock.size(" ", "Default Title"));
        assertNull(BackInStock.size("Default Title", "Default Title"), "a one-size waitlist entry has no size");
        assertEquals("S", BackInStock.size(null, "S"));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void restocked(int quantity, String title, String handle, String variantTitle) {
        var props = """
                {"variant_id":"%s","product_id":"88","quantity":%d,"product_title":"%s","product_handle":"%s",
                 "variant_title":"%s","inventory_item_id":"1"}""".formatted(variant, quantity, title, handle, variantTitle);
        db.inTx(c -> Sql.update(c, "INSERT INTO events (name, props, source) VALUES ('variant_restocked', ?::jsonb, 'shopify')", props));
        dispatcher.dispatchOnce();
    }

    private UUID identity() {
        var id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
        return id;
    }

    /** A shopper with one web push device (refreshed at {@code refreshed}) and a push grant. */
    private UUID subscriber(Instant refreshed) {
        var id = identity();
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver,
                                         last_refreshed_at)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'notify_me', 'push_v1', ?)""",
                    id, "tok-" + UUID.randomUUID(), refreshed.atOffset(ZoneOffset.UTC));
            return Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'push', 'marketing', 'granted', 'test', now() - interval '60 days')""", id);
        });
        return id;
    }

    /** A phone and a WhatsApp marketing opt-in. */
    private void whatsapp(UUID id) {
        var phone = "919" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            return Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'whatsapp', 'marketing', 'granted', 'test', now() - interval '60 days')""", id);
        });
    }

    private void tickAt(Instant t) {
        clock.setIst(t.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime().toString());
        orchestrator.tick(200);
    }

    private void waitlist(UUID identity, String sizeLabel, Instant at) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO stock_waitlist (identity_id, variant_id, product_handle, size_label, created_at)
                VALUES (?, ?, 'linen-kurta', ?, ?)""", identity, variant, sizeLabel, at.atOffset(ZoneOffset.UTC)));
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
