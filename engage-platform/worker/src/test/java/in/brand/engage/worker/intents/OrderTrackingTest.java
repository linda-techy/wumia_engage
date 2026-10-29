package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.ChannelException;
import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.EventDispatcher;
import in.brand.engage.worker.TestClock;
import in.brand.engage.worker.WorkerTestBeans;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T08: order_tracking from Shopify and courier events to WhatsApp and SMS sends. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class OrderTrackingTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject TrackRef refs;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject WorkerTestBeans.FakeSms sms;
    @Inject WorkerTestBeans.FakePush push;

    UUID identity;
    String orderId;
    long shipmentId;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        wa.reset();
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
            for (var t : List.of("order_confirmed_v1", "order_shipped_v1", "out_for_delivery_v1", "ndr_raised_v1", "refund_initiated_v1")) {
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                        VALUES (?, 'en', ?, 'utility', 'utility', 'APPROVED', 'GREEN')
                        ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""", t, t);
            }
            Sql.update(c, "DELETE FROM wa_templates WHERE provider_name = 'order_confirmed_v2'");
            return null;
        });
        config.invalidate();
        identity = customer(true);
        orderId = "test-order-" + UUID.randomUUID();
        db.inTx(c -> Sql.update(c, """
                INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, '#1042', ?, 1249900, now())""",
                orderId, identity));
        shipmentId = count("""
                INSERT INTO shipments (order_id, awb, carrier, tracking_url, status, status_at)
                VALUES (?, ?, 'Delhivery', 'https://track.example/1', 'in_transit', now()) RETURNING id""",
                orderId, "AWB" + UUID.randomUUID().toString().substring(0, 10));
    }

    @Test void a_placed_order_gets_its_confirmation_on_whatsapp() {
        event("order_placed", "{\"order_id\":\"" + orderId + "\",\"total_paise\":1249900}");
        dispatcher.dispatchOnce();

        var call = wa.all.getLast().whatsapp();
        assertEquals("order_confirmed_v1", call.name());
        assertEquals(List.of("Priya", "#1042", "₹12,499"), call.bodyParams());
        assertEquals("waiting|", run(OrderTracking.CONFIRMED, "order:" + orderId), "the push fallback waits 2 h for delivery");
    }

    @Test void the_confirmation_shows_the_piece_once_meta_approves_the_photo_version() {
        var productId = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L));
        var image = "https://cdn.shopify.com/s/files/1/0664/9679/0716/files/test-" + productId + ".png?v=1";
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO products (product_id, handle, title, image_url, updated_at) VALUES (?, ?, 'Kundan Jhumka', ?, now())""",
                    productId, "kundan-" + productId, image);
            Sql.update(c, """
                    INSERT INTO order_lines (order_id, line_no, product_id, quantity, price_paise) VALUES (?, 0, ?, 1, 1249900)""",
                    orderId, productId);
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('order_confirmed_v2', 'en', 'order_confirmed_v2', 'utility', NULL, 'PENDING', 'UNKNOWN')""");
            return null;
        });

        event("order_placed", "{\"order_id\":\"" + orderId + "\",\"total_paise\":1249900}");
        dispatcher.dispatchOnce();
        var pending = wa.all.getLast().whatsapp();
        assertEquals("order_confirmed_v1", pending.name(), "Meta has not approved the photo version yet");
        assertNull(pending.headerImage());

        db.inTx(c -> Sql.update(c, """
                UPDATE wa_templates SET status = 'APPROVED', approved_category = 'utility', quality = 'GREEN'
                 WHERE provider_name = 'order_confirmed_v2'"""));
        var second = "test-order-" + UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, '#1043', ?, 1249900, now())",
                    second, identity);
            Sql.update(c, "INSERT INTO order_lines (order_id, line_no, product_id, quantity, price_paise) VALUES (?, 0, ?, 1, 1249900)",
                    second, productId);
            return null;
        });
        event("order_placed", "{\"order_id\":\"" + second + "\",\"total_paise\":1249900}");
        dispatcher.dispatchOnce();
        var approved = wa.all.getLast().whatsapp();
        assertEquals("order_confirmed_v2", approved.name());
        assertEquals(List.of("Priya", "#1043", "₹12,499"), approved.bodyParams());
        assertEquals("https://engage.wumika.com/media/product/"
                + ProductImages.photoRef(new ProductImages.Photo(productId, image)), approved.headerImage());
        assertEquals("order_confirmed_v2", text("SELECT template_key FROM sends WHERE id = " + wa.all.getLast().sendId()),
                "the send row names what went out");
    }

    @Test void without_a_photo_the_approved_photo_version_falls_back_to_text() {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                VALUES ('order_confirmed_v2', 'en', 'order_confirmed_v2', 'utility', 'utility', 'APPROVED', 'GREEN')"""));
        event("order_placed", "{\"order_id\":\"" + orderId + "\",\"total_paise\":1249900}");
        dispatcher.dispatchOnce();
        assertEquals("order_confirmed_v1", wa.all.getLast().whatsapp().name());
        assertEquals("order_confirmed_v1", text("SELECT template_key FROM sends WHERE id = " + wa.all.getLast().sendId()));
    }

    @Test void shipped_then_out_for_delivery_then_delivered() {
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();

        var shipped = wa.all.getLast().whatsapp();
        assertEquals("order_shipped_v1", shipped.name());
        assertEquals("#1042", shipped.bodyParams().getFirst());
        assertEquals("Delhivery", shipped.bodyParams().get(1));
        var ref = shipped.urlButtonSuffixes().getFirst();
        assertEquals(shipmentId, refs.parse(ref).orElseThrow(), "the Track button opens this parcel");
        assertEquals("waiting|", run(OrderTracking.SHIPPED, "shipment:" + shipmentId), "the SMS waits 2 h for a read");

        event("out_for_delivery", shipmentProps());
        dispatcher.dispatchOnce();
        assertEquals("cancelled|superseded", run(OrderTracking.SHIPPED, "shipment:" + shipmentId), "no shipped SMS after OFD");
        assertEquals("out_for_delivery_v1", wa.all.getLast().whatsapp().name());

        event("order_delivered", shipmentProps());
        dispatcher.dispatchOnce();
        assertEquals("cancelled|delivered", run(OrderTracking.OUT_FOR_DELIVERY, "shipment:" + shipmentId));
        assertEquals(2, wa.all.size(), "delivered sends nothing");
    }

    @Test void a_read_ends_the_shipped_update_so_no_sms_follows() {
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();
        long sendId = wa.all.getLast().sendId();

        event("wa_read", "{\"send_id\":" + sendId + "}");
        dispatcher.dispatchOnce();
        assertEquals("succeeded|read", run(OrderTracking.SHIPPED, "shipment:" + shipmentId));

        clock.setIst("2026-09-28T14:30:00");
        orchestrator.tick(200);
        assertEquals(0L, count("SELECT count(*) FROM sends WHERE identity_id = ? AND channel = 'sms'", identity));
    }

    @Test void an_incapable_number_goes_straight_to_sms() {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel, state, strike_days, recheck_after)
                VALUES (?, 'whatsapp', 'INCAPABLE', '{2026-09-01,2026-09-03}', now() + interval '100 days')""", identity));
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();

        assertTrue(wa.all.isEmpty(), "no WhatsApp attempt");
        assertEquals("failed|failed:dlt_pending", run(OrderTracking.SHIPPED, "shipment:" + shipmentId),
                "the SMS step ran at once; it waits for DLT, so nothing left");
        assertTrue(sms.all.isEmpty());
    }

    @Test void a_131026_moves_on_at_once_to_push_then_sms() {
        wa.fail = new ChannelException.Permanent("wa_131026", "undeliverable", false, true, List.of());
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();

        assertEquals(1, wa.all.size());
        assertEquals("2", text("SELECT max(a.step_index)::text FROM cascade_attempts a JOIN cascade_runs r ON r.id = a.run_id "
                + "WHERE r.intent_key = 'order_shipped' AND r.subject_key = 'shipment:" + shipmentId + "'"),
                "no 2 h wait: push (no device here, skipped) and SMS at once");
    }

    /** SMS on hold (owner, 2026-10-29): a customer without WhatsApp who allowed notifications gets the update as a push. */
    @Test void without_whatsapp_but_with_push_the_shipping_update_comes_as_a_push_at_once() {
        var other = customer(false);
        pushOn(other);
        db.inTx(c -> Sql.update(c, "UPDATE orders SET identity_id = ? WHERE id = ?", other, orderId));
        push.last = null;
        eventFor(other, "order_shipped", shipmentProps(), clock.instant());
        dispatcher.dispatchOnce();

        assertTrue(wa.all.isEmpty());
        assertNotNull(push.last, attemptsOf(OrderTracking.SHIPPED));
        assertEquals("push_order_shipped_v1", push.last.templateKey());
        assertTrue(push.last.url().contains("/apps/push/t/"), push.last.url());
    }

    @Test void a_delivered_whatsapp_ends_the_update_so_no_push_follows() {
        pushOn(identity);
        push.last = null;
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();
        long sendId = wa.all.getLast().sendId();
        event("wa_delivered", "{\"send_id\":" + sendId + "}");
        dispatcher.dispatchOnce();
        assertEquals("succeeded|delivered", run(OrderTracking.SHIPPED, "shipment:" + shipmentId));
        clock.setIst("2026-09-28T14:30:00");
        orchestrator.tick(200);
        assertNull(push.last, "delivered on WhatsApp: no push as well");
    }

    @Test void an_undelivered_whatsapp_gets_the_push_after_its_wait() {
        pushOn(identity);
        push.last = null;
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();
        assertNull(push.last, "WhatsApp first");
        clock.setIst("2026-09-28T14:01:00");
        orchestrator.tick(200);
        assertNotNull(push.last, attemptsOf(OrderTracking.SHIPPED));
        assertEquals("push_order_shipped_v1", push.last.templateKey());
    }

    @Test void customers_without_a_whatsapp_opt_in_keep_shopifys_notifications() {
        var other = customer(false);
        db.inTx(c -> Sql.update(c, "UPDATE orders SET identity_id = ? WHERE id = ?", other, orderId));
        eventFor(other, "order_shipped", shipmentProps(), clock.instant());
        dispatcher.dispatchOnce();
        assertNull(run(OrderTracking.SHIPPED, "shipment:" + shipmentId));
    }

    @Test void a_day_old_event_or_a_cancelled_order_sends_nothing() {
        eventFor(identity, "order_shipped", shipmentProps(), clock.instant().minus(Duration.ofHours(25)));
        dispatcher.dispatchOnce();
        assertNull(run(OrderTracking.SHIPPED, "shipment:" + shipmentId));

        db.inTx(c -> Sql.update(c, "UPDATE orders SET cancelled_at = now() WHERE id = ?", orderId));
        event("out_for_delivery", shipmentProps());
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty());
    }

    @Test void a_cancelled_order_ends_its_live_updates() {
        event("order_shipped", shipmentProps());
        dispatcher.dispatchOnce();
        event("order_cancelled", "{\"order_id\":\"" + orderId + "\"}");
        dispatcher.dispatchOnce();
        assertEquals("cancelled|order_cancelled", run(OrderTracking.SHIPPED, "shipment:" + shipmentId));
    }

    @Test void rupees_use_indian_grouping() {
        assertEquals("₹12,499", OrderTracking.rupees(1249900));
        assertEquals("₹1,23,456.50", OrderTracking.rupees(12345650));
        assertEquals("₹799", OrderTracking.rupees(79900));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private String shipmentProps() {
        return "{\"order_id\":\"" + orderId + "\",\"shipment_id\":" + shipmentId + ",\"awb\":\"1234567890\",\"carrier\":\"Delhivery\"}";
    }

    private UUID customer(boolean waOptIn) {
        var id = UUID.randomUUID();
        var phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", id);
            if (waOptIn) {
                Sql.update(c, """
                        INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                        VALUES (?, 'whatsapp', 'transactional', 'granted', 'cart_attr', now() - interval '1 day')""", id);
            }
            return null;
        });
        return id;
    }

    /** A live browser subscription with the soft-ask grant. */
    private void pushOn(UUID who) {
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'browse', 'push_v1')""", who, "tok-" + UUID.randomUUID());
            for (var purpose : List.of("marketing", "transactional")) {
                Sql.update(c, """
                        INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                        VALUES (?, 'push', CAST(? AS purpose), 'granted', 'test', now() - interval '1 day')""", who, purpose);
            }
            return null;
        });
    }

    private String attemptsOf(String intent) {
        return text("SELECT string_agg(a.step_index || ':' || a.channel || ':' || a.result || ':' || COALESCE(a.reason,''), '; ' ORDER BY a.id) "
                + "FROM cascade_attempts a JOIN cascade_runs r ON r.id = a.run_id WHERE r.intent_key = '" + intent + "'");
    }

    private void event(String name, String props) {
        eventFor(identity, name, props, clock.instant());
    }

    private void eventFor(UUID who, String name, String props, Instant at) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'test', ?)",
                who, name, props, at.atOffset(ZoneOffset.UTC)));
    }

    private String run(String intent, String subject) {
        return text("SELECT status || '|' || COALESCE(outcome, '') FROM cascade_runs WHERE intent_key = '" + intent
                + "' AND subject_key = '" + subject + "' ORDER BY id DESC LIMIT 1");
    }

    private String text(String sql) {
        return db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
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
}
