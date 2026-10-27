package in.brand.engage.worker.inbox;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.whatsapp.WaPayloads;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.StaffPushKeys;
import in.brand.engage.persistence.WaInboxLog;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.EventDispatcher;
import in.brand.engage.worker.TestClock;
import in.brand.engage.worker.WorkerTestBeans;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * V50: the greeting / away auto-reply and its menu, staff replies and the
 * follow-up, all as events through the orchestrator and policy; and staff
 * alerts. WhatsApp and push are faked.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class InboxAutomationTest {

    @Singleton
    @Replaces(PushTransport.Http.class)
    static class FakePush implements PushTransport {
        final List<String[]> calls = new ArrayList<>();
        int status = 201;

        @Override public int post(String endpoint, String authorization, int ttl, String topic) {
            calls.add(new String[] {endpoint, authorization, String.valueOf(ttl), topic});
            return status;
        }
    }

    @Inject EventDispatcher dispatcher;
    @Inject StaffAlerts alerts;
    @Inject WorkerTestBeans.FakeWhatsApp wa;
    @Inject FakePush push;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;

    UUID mary;
    UUID operator;
    String phone;

    @BeforeEach void reset() {
        clock.setIst("2026-10-27T12:00:00");
        wa.reset();
        push.calls.clear();
        push.status = 201;
        mary = UUID.randomUUID();
        operator = UUID.randomUUID();
        phone = "919" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            }
            try (var st = c.createStatement()) {
                st.execute("UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL");
                st.execute("UPDATE cascade_runs SET status = 'cancelled', outcome = 'test_reset' WHERE status IN ('active','waiting')");
                st.execute("TRUNCATE config_versions CASCADE");
                st.execute("TRUNCATE wa_messages, wa_threads, staff_push_subscriptions");
                st.execute("UPDATE inbox_job_state SET last_message_id = 0");
            }
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", mary);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)", mary, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs, wa_window_until) VALUES (?, '{\"first_name\":\"Mary\"}', ?)",
                    mary, clock.instant().plus(Duration.ofHours(23)).atOffset(ZoneOffset.UTC));
            Sql.update(c, "INSERT INTO operators (id, email, full_name, password_hash, status) VALUES (?, ?, 'Staff', 'x', 'active')",
                    operator, "staff-" + operator + "@example.com");
            return null;
        });
        config.invalidate();
    }

    /** What Meta's webhook leaves behind for one customer message: the inbox row and the event. */
    String inbound(String kind, String body, String eventName, String context, Duration ago) {
        var wamid = "wamid.in." + UUID.randomUUID();
        var at = clock.instant().minus(ago);
        db.inTx(c -> {
            WaInboxLog.inbound(c, mary, wamid, kind, body, null, null, null, context, at);
            return Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, ?, ?::jsonb, 'meta', ?)",
                    mary, eventName, "{\"message_id\":\"" + wamid + "\",\"type\":\"" + kind + "\""
                            + (context == null ? "" : ",\"context_wamid\":\"" + context + "\"") + "}", at.atOffset(ZoneOffset.UTC));
        });
        return wamid;
    }

    String inbound(String kind, String body) {
        return inbound(kind, body, "wa_message_received", null, Duration.ZERO);
    }

    void tap(String reply, String title) {
        inbound("interactive", title, reply, null, Duration.ZERO);
    }

    void staffEvent(String name, String props) {
        db.inTx(c -> Sql.update(c, "INSERT INTO events (identity_id, name, props, source) VALUES (?, ?, ?::jsonb, 'admin')", mary, name, props));
    }

    RenderedMessage last() {
        return wa.all.getLast();
    }

    String q(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    void setting(String key, String selector, String json) {
        db.inTx(c -> Sql.update(c, "INSERT INTO config_versions (key, selector, value, changed_by, reason) VALUES (?, ?, CAST(? AS jsonb), ?, 'test')",
                key, selector, json, operator));
        config.invalidate();
    }

    /* ----------------------------- auto-reply ----------------------------- */

    @Test void in_hours_a_new_conversation_gets_the_greeting_menu_once() {
        inbound("text", "Hi, is the jhumka in stock?");
        dispatcher.dispatchOnce();

        assertEquals(1, wa.all.size());
        var m = last();
        assertEquals("inbox_greeting", m.templateKey());
        assertNull(m.whatsapp(), "a free-form reply, not a Meta template");
        assertTrue(m.body().startsWith("Hi Mary, welcome to WUMIKA!"), m.body());
        var buttons = WaPayloads.replyButtons(m);
        assertEquals(3, buttons.size());
        assertTrue(buttons.toString().contains("inbox_track") && buttons.toString().contains("Shop bestsellers"));
        assertEquals("interactive|inbox_greeting|true", q("""
                SELECT kind || '|' || template_key || '|' || (sent_by IS NULL) FROM wa_messages WHERE direction = 'out'"""));
        assertEquals("sent|service", q("SELECT status || '|' || category FROM sends WHERE identity_id = ? AND template_key = 'inbox_greeting'", mary));

        clock.setIst("2026-10-27T12:05:00");
        inbound("text", "Also, do you have it in silver?");
        dispatcher.dispatchOnce();
        assertEquals(1, wa.all.size(), "one greeting per conversation");
    }

    @Test void after_hours_the_away_message_names_the_hours_and_quiet_hours_do_not_hold_it() {
        clock.setIst("2026-10-27T22:00:00");
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                clock.instant().plus(Duration.ofHours(23)).atOffset(ZoneOffset.UTC), mary));
        inbound("text", "Hello?");
        dispatcher.dispatchOnce();
        assertEquals("inbox_away", last().templateKey());
        assertTrue(last().body().contains("Our team is online 10 AM to 7 PM and will reply after 10 AM"), last().body());

        clock.setIst("2026-10-27T23:00:00");
        inbound("text", "Anyone there?");
        dispatcher.dispatchOnce();
        assertEquals(1, wa.all.size(), "not repeated within 12 h");
    }

    @Test void track_my_order_answers_with_the_courier_status_and_link() {
        var order = "o-inbox-" + UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES (?, '#1042', ?, 149900, now())", order, mary);
            return Sql.update(c, """
                    INSERT INTO shipments (order_id, awb, carrier, tracking_url, status, status_at)
                    VALUES (?, ?, 'Delhivery', 'https://t.example/1', 'in_transit', now())""", order, "AWB" + UUID.randomUUID().toString().substring(0, 8));
        });
        tap("inbox_track", "Track my order");
        dispatcher.dispatchOnce();
        assertEquals("inbox_auto_reply", last().templateKey());
        assertTrue(last().body().startsWith("Your order #1042 is on its way with Delhivery (AWB AWB"), last().body());
        assertTrue(last().body().endsWith("Track it here: https://t.example/1"), last().body());

        tap("inbox_shop", "Shop bestsellers");
        dispatcher.dispatchOnce();
        assertTrue(last().body().contains("collections/all?sort_by=best-selling&utm_source=whatsapp&utm_medium=inbox"), last().body());
    }

    @Test void no_order_gets_asked_for_the_order_number() {
        tap("inbox_track", "Track my order");
        dispatcher.dispatchOnce();
        assertTrue(last().body().startsWith("We couldn't find an order linked to this number"), last().body());
    }

    @Test void staff_in_the_chat_replies_to_our_messages_and_old_messages_get_no_greeting() {
        db.inTx(c -> {
            WaInboxLog.outbound(c, mary, "wamid.staff." + UUID.randomUUID(), "text", "Hello Mary", null, null, operator,
                    clock.instant().minus(Duration.ofMinutes(30)));
            return null;
        });
        inbound("text", "Thanks!");
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty(), "staff are talking to her");

        db.inTx(c -> Sql.update(c, "TRUNCATE wa_messages, wa_threads"));
        inbound("text", "Is COD available?", "wa_message_received", "wamid.our-cart-reminder", Duration.ZERO);
        inbound("text", "Hello", "wa_message_received", null, Duration.ofMinutes(20));
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty(), "a reply to one of our messages, and one too old to greet");
    }

    @Test void the_whatsapp_halt_blocks_it_on_the_record_and_the_setting_turns_it_off() {
        setting("halt.channel", "whatsapp", "true");
        inbound("text", "Hi");
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty());
        assertEquals("blocked", q("SELECT status FROM sends WHERE identity_id = ? AND template_key = 'inbox_greeting'", mary));

        setting("halt.channel", "whatsapp", "false");
        setting("inbox.auto_reply", "*", "false");
        clock.setIst("2026-10-28T12:00:00");
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                clock.instant().plus(Duration.ofHours(23)).atOffset(ZoneOffset.UTC), mary));
        inbound("text", "Hi again");
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty());
    }

    /* ------------------------------ staff sends ----------------------------- */

    @Test void a_staff_reply_goes_out_through_policy_with_their_name_on_it() {
        staffEvent("inbox_staff_reply", "{\"text\":\"Yes, it is in stock!\",\"operator_id\":\"" + operator + "\"}");
        dispatcher.dispatchOnce();
        assertEquals("inbox_staff_reply", last().templateKey());
        assertEquals("Yes, it is in stock!", last().body());
        assertEquals("text|" + operator, q("SELECT kind || '|' || sent_by FROM wa_messages WHERE direction = 'out'"));
    }

    @Test void after_the_window_a_staff_reply_is_refused_and_the_thread_says_why() {
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                clock.instant().minus(Duration.ofMinutes(1)).atOffset(ZoneOffset.UTC), mary));
        staffEvent("inbox_staff_reply", "{\"text\":\"Hello?\",\"operator_id\":\"" + operator + "\"}");
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty());
        assertEquals("failed|NO_WHATSAPP_OPT_IN|Hello?|" + operator,
                q("SELECT status || '|' || failed_reason || '|' || body || '|' || sent_by FROM wa_messages WHERE direction = 'out'"));
    }

    @Test void the_follow_up_template_needs_a_whatsapp_opt_in() {
        db.inTx(c -> {
            Sql.update(c, "UPDATE profiles SET wa_window_until = NULL WHERE identity_id = ?", mary);
            return Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('support_followup_v1', 'en', 'support_followup_v1', 'utility', 'utility', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED', quality = 'GREEN'""");
        });
        staffEvent("inbox_followup", "{\"operator_id\":\"" + operator + "\"}");
        dispatcher.dispatchOnce();
        assertTrue(wa.all.isEmpty());
        assertEquals("failed|NO_WHATSAPP_OPT_IN", q("SELECT status || '|' || failed_reason FROM wa_messages WHERE direction = 'out'"));

        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, 'whatsapp', 'transactional', 'granted', 'cart_attr', now() - interval '1 day')""", mary));
        staffEvent("inbox_followup", "{\"operator_id\":\"" + operator + "\"}");
        dispatcher.dispatchOnce();
        assertEquals("support_followup_v1", last().whatsapp().name());
        assertEquals(List.of("Mary"), last().whatsapp().bodyParams());
        assertEquals("template|" + operator, q("SELECT kind || '|' || sent_by FROM wa_messages WHERE direction = 'out' AND status = 'sent'"));
    }

    /* ---------------------------- staff alerts ---------------------------- */

    void subscribe(String endpoint) {
        db.inTx(c -> Sql.update(c, "INSERT INTO staff_push_subscriptions (operator_id, endpoint) VALUES (?, ?)", operator, endpoint));
    }

    void plainInbound(String kind, String body) {
        db.inTx(c -> WaInboxLog.inbound(c, mary, "wamid.in." + UUID.randomUUID(), kind, body, null, null, null, null, clock.instant()));
    }

    @Test void a_new_message_alerts_each_device_once_with_a_signed_empty_push() throws Exception {
        subscribe("https://fcm.googleapis.com/fcm/send/abc");
        plainInbound("text", "Hi");
        assertEquals(1, alerts.runOnce());
        assertEquals(0, alerts.runOnce(), "nothing new");
        var call = push.calls.getFirst();
        assertEquals("https://fcm.googleapis.com/fcm/send/abc", call[0]);
        assertEquals("inbox", call[3]);
        verifyVapid(call[1], "https://fcm.googleapis.com");
        assertNotNull(q("SELECT last_sent_at FROM staff_push_subscriptions"));
    }

    @Test void after_hours_alerts_wait_for_opening() {
        subscribe("https://fcm.googleapis.com/fcm/send/abc");
        clock.setIst("2026-10-27T23:30:00");
        plainInbound("text", "Hi");
        assertEquals(0, alerts.runOnce());
        clock.setIst("2026-10-28T10:00:05");
        assertEquals(1, alerts.runOnce());
    }

    @Test void menu_taps_the_auto_reply_answered_alert_nobody_and_a_gone_device_is_dropped() {
        subscribe("https://fcm.googleapis.com/fcm/send/abc");
        plainInbound("interactive", "Shop bestsellers");
        assertEquals(0, alerts.runOnce());
        assertTrue(push.calls.isEmpty());

        push.status = 410;
        plainInbound("interactive", "Talk to us");
        assertEquals(0, alerts.runOnce());
        assertEquals(1, push.calls.size());
        assertEquals("0", q("SELECT count(*) FROM staff_push_subscriptions"), "the browser unsubscribed");
    }

    /** The JWT verifies against the public key the console subscribed with. */
    void verifyVapid(String header, String audience) throws Exception {
        assertTrue(header.startsWith("vapid t="), header);
        var t = header.substring(8, header.indexOf(", k="));
        var k = header.substring(header.indexOf(", k=") + 4);
        assertEquals(db.inTx(c -> StaffPushKeys.load(c).publicKey()), k);
        var parts = t.split("\\.");
        var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"" + audience + "\"") && claims.contains("\"sub\":\"mailto:support@wumika.com\""), claims);

        var point = Base64.getUrlDecoder().decode(k);
        assertEquals(65, point.length);
        var params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        var spec = new ECPublicKeySpec(new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(point, 33, 65))), params.getParameterSpec(ECParameterSpec.class));
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(spec));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }
}
