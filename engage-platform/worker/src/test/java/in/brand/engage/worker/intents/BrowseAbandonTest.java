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

/** browse_abandon from pixel product views to sends rows, against Postgres. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class BrowseAbandonTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakePush push;

    String clientId;
    String anon;

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
        clientId = "cid-" + UUID.randomUUID();
        anon = UUID.randomUUID().toString();
    }

    @Test void three_views_then_quiet_sends_one_push_naming_the_most_viewed_product() {
        var id = linkedSubscriber();
        var start = clock.instant().minus(Duration.ofHours(5));
        view("8801", "Linen Kurta", "linen-kurta", start);
        view("8802", "Silk Dupatta", "silk-dupatta", start.plus(Duration.ofMinutes(5)));
        view("8801", "Linen Kurta", "linen-kurta", start.plus(Duration.ofMinutes(9)));
        dispatcher.dispatchOnce();

        assertEquals(1, push.all.size());
        var msg = push.all.getFirst();
        assertEquals("push_browse_abandon_v1", msg.templateKey());
        assertEquals("Still thinking about it?", msg.title());
        assertTrue(msg.body().startsWith("Linen Kurta is still in stock."), msg.body());
        assertTrue(msg.url().contains("/products/linen-kurta?variant=v8801&utm_source=engage"), msg.url());
        assertEquals(Duration.ofHours(24), msg.ttl());
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'browse_abandon' AND subject_key = ?",
                id.toString()), "the third view starts the one run; nothing restarts it");
    }

    @Test void the_push_waits_four_hours_after_the_last_view_and_a_new_view_restarts_the_wait() {
        var id = linkedSubscriber();
        var now = clock.instant();
        view("8801", "Linen Kurta", "linen-kurta", now.minus(Duration.ofMinutes(20)));
        view("8801", "Linen Kurta", "linen-kurta", now.minus(Duration.ofMinutes(10)));
        view("8801", "Linen Kurta", "linen-kurta", now);
        dispatcher.dispatchOnce();
        view("8802", "Silk Dupatta", "silk-dupatta", now.plus(Duration.ofMinutes(15)));
        dispatcher.dispatchOnce();
        assertEquals(0, push.all.size());
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE intent_key = 'browse_abandon' AND subject_key = ? AND outcome = 'still_browsing'", id.toString()));

        clock.setIst("2026-09-28T16:31:00");                         // 4 h 31 min after the third view
        orchestrator.tick(100);
        assertEquals(0, push.all.size(), "the fourth view moved the push to 16:45");

        clock.setIst("2026-09-28T16:46:00");
        orchestrator.tick(100);
        assertEquals(1, push.all.size());
    }

    @Test void two_views_are_not_enough() {
        var id = linkedSubscriber();
        var start = clock.instant().minus(Duration.ofHours(5));
        view("8801", "Linen Kurta", "linen-kurta", start);
        view("8802", "Silk Dupatta", "silk-dupatta", start.plus(Duration.ofMinutes(5)));
        dispatcher.dispatchOnce();

        assertNull(run(id));
    }

    @Test void views_across_a_half_hour_gap_are_two_sessions() {
        linkedSubscriber();
        var start = clock.instant().minus(Duration.ofHours(6));
        view("8801", "Linen Kurta", "linen-kurta", start);
        view("8802", "Silk Dupatta", "silk-dupatta", start.plus(Duration.ofMinutes(5)));
        view("8803", "Cotton Saree", "cotton-saree", start.plus(Duration.ofMinutes(40)));
        view("8801", "Linen Kurta", "linen-kurta", start.plus(Duration.ofMinutes(45)));
        dispatcher.dispatchOnce();

        assertEquals(0, push.all.size());
    }

    @Test void an_add_to_cart_in_the_session_means_no_push() {
        var id = linkedSubscriber();
        var start = clock.instant().minus(Duration.ofMinutes(30));
        view("8801", "Linen Kurta", "linen-kurta", start);
        view("8802", "Silk Dupatta", "silk-dupatta", start.plus(Duration.ofMinutes(1)));
        view("8803", "Cotton Saree", "cotton-saree", start.plus(Duration.ofMinutes(2)));
        dispatcher.dispatchOnce();
        assertEquals("active|", run(id), "three views start a run");

        pixel("product_added_to_cart", "8803", "Cotton Saree", "cotton-saree", start.plus(Duration.ofMinutes(3)));
        view("8801", "Linen Kurta", "linen-kurta", start.plus(Duration.ofMinutes(4)));
        dispatcher.dispatchOnce();

        assertEquals("cancelled|added_to_cart", run(id));
    }

    @Test void a_cart_change_for_the_person_ends_it() {
        var id = linkedSubscriber();
        var start = clock.instant().minus(Duration.ofMinutes(30));
        for (int i = 0; i < 3; i++) view("8801", "Linen Kurta", "linen-kurta", start.plus(Duration.ofMinutes(i)));
        dispatcher.dispatchOnce();

        db.inTx(c -> Sql.update(c, """
                INSERT INTO events (identity_id, name, props, source) VALUES (?, 'cart_updated', '{"cart_token":"none"}', 'shopify')""", id));
        dispatcher.dispatchOnce();

        assertEquals("cancelled|added_to_cart", run(id));
    }

    @Test void a_browser_nobody_has_linked_is_skipped_and_no_identity_is_made() {
        long identities = count("SELECT count(*) FROM identities");
        var start = clock.instant().minus(Duration.ofHours(5));
        for (int i = 0; i < 4; i++) view("8801", "Linen Kurta", "linen-kurta", start.plus(Duration.ofMinutes(i)));
        dispatcher.dispatchOnce();

        assertEquals(0, push.all.size());
        assertEquals(identities, count("SELECT count(*) FROM identities"));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private void view(String productId, String title, String handle, Instant at) {
        pixel("product_viewed", productId, title, handle, at);
    }

    private void pixel(String name, String productId, String title, String handle, Instant at) {
        var props = """
                {"client_id":"%s","anon_id":"%s","product_id":"%s","variant_id":"v%s","product_title":"%s",
                 "product_handle":"%s","at":"%s"}""".formatted(clientId, anon, productId, productId, title, handle, at);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO events (name, props, source, dedupe_key, occurred_at) VALUES (?, ?::jsonb, 'pixel', ?, ?)""",
                name, props, "pixel:" + clientId + ":" + name + ":" + at, at.atOffset(ZoneOffset.UTC)));
    }

    /** A shopper whose browser the push embed linked (identity key anon), with a device and a push grant. */
    private UUID linkedSubscriber() {
        var id = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value) VALUES (?, 'anon', ?)", id, anon);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver,
                                         last_refreshed_at)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1', now())""",
                    id, "tok-" + UUID.randomUUID());
            return Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'push', 'marketing', 'granted', 'test', now() - interval '60 days')""", id);
        });
        return id;
    }

    private String run(UUID identity) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs
                     WHERE intent_key = 'browse_abandon' AND subject_key = ? ORDER BY id DESC LIMIT 1""", identity.toString());
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
