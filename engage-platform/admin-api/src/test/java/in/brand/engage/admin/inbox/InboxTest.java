package in.brand.engage.admin.inbox;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.WaInboxLog;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The WhatsApp inbox: threads, the 24 h reply rule, queued staff sends (the
 * worker sends them through policy), saved answers, alert subscriptions and
 * read receipts. Meta is faked.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class InboxTest {

    /** Records what would have gone to Meta; nothing leaves the JVM. */
    static class FakeGraph extends WhatsAppGraph {
        final List<String> calls = new ArrayList<>();
        FakeGraph(JsonMapper json) { super(json, "v25.0", "123", "token"); }
        @Override public void markRead(String wamid) { calls.add("read:" + wamid); }
        @Override public Media media(String mediaId) { calls.add("media:" + mediaId); return new Media("image/jpeg", new byte[] {1, 2, 3}); }
    }

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;
    @Inject WhatsAppGraph graph;

    @MockBean(WhatsAppGraph.class)
    WhatsAppGraph fakeGraph(JsonMapper json) {
        return new FakeGraph(json);
    }

    UUID mary;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        ((FakeGraph) graph).calls.clear();
        mary = UUID.fromString(data.createIdentity("mary@example.com", "918606572870"));
        db.inTx(c -> {
            Sql.update(c, "DELETE FROM wa_templates WHERE key = 'support_followup_v1'");
            Sql.update(c, "DELETE FROM events WHERE name IN ('inbox_staff_reply', 'inbox_followup', 'inbox_order_help')");
            Sql.update(c, "DELETE FROM wa_templates WHERE key = 'support_order_v1'");
            Sql.update(c, "TRUNCATE staff_push_subscriptions");
            WaInboxLog.inbound(c, mary, "wamid.in1", "image", "Is this in stock?", "media-1", "image/jpeg", null, null,
                    Instant.now().minus(1, ChronoUnit.HOURS));
            return null;
        });
    }

    void window(Instant until) {
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                until.atOffset(java.time.ZoneOffset.UTC), mary));
    }

    String q(String sql) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test void the_list_and_thread_show_the_customers_message_and_opening_is_logged() {
        var viewer = data.loginAsViewer(client);
        var unread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/unread").bearerAuth(viewer), Map.class);
        assertEquals(1, ((Number) unread.get("threads")).intValue());
        var denied = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(viewer), Argument.listOf(Map.class)));
        assertEquals(403, denied.getStatus().getCode());

        var analyst = data.loginAsAnalyst(client);
        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(1, list.size());
        assertEquals("Mary", list.getFirst().get("name"));
        assertEquals(1, ((Number) list.getFirst().get("unread")).intValue());
        assertEquals(1, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?q=86065").bearerAuth(analyst), Argument.listOf(Map.class)).size());
        assertEquals(0, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?q=nobody").bearerAuth(analyst), Argument.listOf(Map.class)).size());

        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        var messages = (List<Map<String, Object>>) thread.get("messages");
        assertEquals("Is this in stock?", messages.getFirst().get("body"));
        assertEquals(Boolean.TRUE, messages.getFirst().get("hasMedia"));
        assertEquals("mary@example.com", thread.get("email"));
        assertEquals("1", q("SELECT count(*) FROM pii_unmask_log WHERE field = 'whatsapp_inbox'"));

        var media = client.toBlocking().exchange(HttpRequest.GET("/api/inbox/media/" + messages.getFirst().get("id")).bearerAuth(analyst), byte[].class);
        assertEquals("image/jpeg", media.getContentType().orElseThrow().toString());
    }

    @Test void new_chat_lists_every_customer_masked_with_what_whatsapp_allows() {
        var ravi = UUID.fromString(data.createIdentity("ravi@example.com", "919876543210"));
        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, 'whatsapp', 'transactional', 'granted', 'checkout_notice', now() - interval '1 day')""", mary));
        var analyst = data.loginAsAnalyst(client);
        var all = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/customers").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(2, all.size(), "everyone, opted in or not");
        assertFalse(String.valueOf(all).contains("8606572870") || String.valueOf(all).contains("ravi@example.com"), "masked");
        var byPhone = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/customers?q=98765").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(1, byPhone.size());
        assertEquals(Boolean.FALSE, byPhone.getFirst().get("waOptIn"));
        assertEquals(Boolean.FALSE, byPhone.getFirst().get("hasChat"));
        var maryRow = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/customers?q=mary@example").bearerAuth(analyst), Argument.listOf(Map.class)).getFirst();
        assertEquals(Boolean.TRUE, maryRow.get("waOptIn"));

        // Opening a customer who never wrote: an empty chat, logged as a lookup (it shows their number).
        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + ravi).bearerAuth(analyst), Map.class);
        assertEquals(List.of(), thread.get("messages"));
        assertEquals(Boolean.FALSE, thread.get("waOptIn"));
        assertEquals("1", q("SELECT count(*) FROM pii_unmask_log WHERE field = 'whatsapp_start'"));
    }

    @Test void the_order_message_needs_a_whatsapp_opt_in_and_one_of_their_orders() {
        var ravi = UUID.fromString(data.createIdentity("ravi@example.com", "919876543210"));
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o-r1', 'WUM1183', ?, 99900, now())", ravi);
            return Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, status, quality)
                    VALUES ('support_order_v1', 'en', 'support_order_v1', 'utility', 'APPROVED', 'GREEN')
                    ON CONFLICT (provider_name, language) DO UPDATE SET status = 'APPROVED'""");
        });
        var analyst = data.loginAsAnalyst(client);
        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + ravi).bearerAuth(analyst), Map.class);
        var starters = (List<Map<String, Object>>) thread.get("starters");
        assertEquals(1, starters.size(), "no follow-up for someone who never wrote");
        assertEquals("support_order_v1", starters.getFirst().get("key"));
        assertEquals(List.of("WUM1183"), starters.getFirst().get("orderNumbers"));

        var noOptIn = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(HttpRequest.POST(
                "/api/inbox/" + ravi + "/template", Map.of("templateKey", "support_order_v1", "orderNumber", "WUM1183")).bearerAuth(analyst), Map.class));
        assertEquals(409, noOptIn.getStatus().getCode(), "WhatsApp needs an opt-in for a business-initiated message");

        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, 'whatsapp', 'transactional', 'granted', 'checkout_notice', now() - interval '1 day')""", ravi));
        var notTheirs = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(HttpRequest.POST(
                "/api/inbox/" + ravi + "/template", Map.of("templateKey", "support_order_v1", "orderNumber", "WUM9999")).bearerAuth(analyst), Map.class));
        assertEquals(400, notTheirs.getStatus().getCode());
        var res = client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + ravi + "/template",
                Map.of("templateKey", "support_order_v1", "orderNumber", "WUM1183")).bearerAuth(analyst), Map.class);
        assertEquals(202, res.code());
        assertEquals("WUM1183", q("SELECT props->>'order_number' FROM events WHERE name = 'inbox_order_help'"));
    }

    @Test void a_chat_refreshing_all_day_is_logged_once_and_never_uses_up_the_lookup_limit() {
        var analyst = data.loginAsAnalyst(client);
        for (int i = 0; i < 60; i++) {   // the open chat polls every 5 s: 60 polls are five minutes
            client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        }
        assertEquals("1", q("SELECT count(*) FROM pii_unmask_log WHERE field = 'whatsapp_inbox'"), "one audit row a day");

        // Even 50 conversations in a day (other customers) leave lookups their full allowance.
        db.inTx(c -> Sql.update(c, """
                INSERT INTO pii_unmask_log (operator_id, identity_id, field, reason)
                SELECT o.id, NULL, 'whatsapp_inbox', 'WhatsApp inbox' FROM operators o, generate_series(1, 50)
                 WHERE o.email = 'analyst@example.com'"""));
        var again = client.toBlocking().exchange(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        assertEquals(200, again.code(), "conversations are not lookups");
    }

    @Test void opening_an_unread_chat_clears_the_badge_and_shows_the_customer_blue_ticks_once() {
        var analyst = data.loginAsAnalyst(client);
        client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/read", Map.of()).bearerAuth(analyst));
        var unread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/unread").bearerAuth(analyst), Map.class);
        assertEquals(0, ((Number) unread.get("threads")).intValue());
        client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/read", Map.of()).bearerAuth(analyst));
        assertEquals(List.of("read:wamid.in1"), ((FakeGraph) graph).calls, "nothing new the second time");
    }

    @Test void the_thread_shows_each_orders_courier_status() {
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o-1', '#1042', ?, 149900, now())", mary);
            return Sql.update(c, """
                    INSERT INTO shipments (order_id, awb, carrier, tracking_url, status, status_at)
                    VALUES ('o-1', 'AWB77', 'Delhivery', 'https://t.example/AWB77', 'out_for_delivery', now())""");
        });
        var analyst = data.loginAsAnalyst(client);
        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        var order = ((List<Map<String, Object>>) thread.get("orders")).getFirst();
        assertEquals("#1042", order.get("orderNumber"));
        assertEquals("out_for_delivery", order.get("shipmentStatus"));
        assertEquals("https://t.example/AWB77", order.get("trackingUrl"));
    }

    @Test void inside_the_window_a_reply_is_queued_for_the_worker_with_the_staff_member() {
        window(Instant.now().plus(23, ChronoUnit.HOURS));
        var analyst = data.loginAsAnalyst(client);
        var res = client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/reply", Map.of("text", "Yes, in stock!")).bearerAuth(analyst), Map.class);
        assertEquals(202, res.code());
        assertEquals("Yes, in stock!|true", q("""
                SELECT e.props->>'text' || '|' || (e.props->>'operator_id' = o.id::text)
                  FROM events e JOIN operators o ON o.email = 'analyst@example.com' WHERE e.name = 'inbox_staff_reply'"""));
        assertTrue(((FakeGraph) graph).calls.isEmpty(), "the console never calls WhatsApp to send");
        assertEquals("1", q("SELECT count(*) FROM pii_unmask_log WHERE field = 'whatsapp_reply'"));
    }

    @Test void after_the_window_a_reply_is_refused_and_only_the_approved_follow_up_may_be_queued() {
        window(Instant.now().minus(1, ChronoUnit.MINUTES));
        var analyst = data.loginAsAnalyst(client);
        var closed = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/reply", Map.of("text", "Hello?")).bearerAuth(analyst), Map.class));
        assertEquals(409, closed.getStatus().getCode());

        var pending = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/template", Map.of("templateKey", "support_followup_v1")).bearerAuth(analyst), Map.class));
        assertEquals(409, pending.getStatus().getCode(), "not approved by Meta yet");
        var other = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/template", Map.of("templateKey", "campaign_diwali_v1")).bearerAuth(analyst), Map.class));
        assertEquals(400, other.getStatus().getCode(), "marketing templates are not sent from the inbox");

        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'whatsapp', 'transactional', 'granted', 'checkout_notice', now() - interval '1 day')""", mary);
            return Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, status, quality)
                    VALUES ('support_followup_v1', 'en', 'support_followup_v1', 'utility', 'APPROVED', 'GREEN')""");
        });
        var res = client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/template",
                Map.of("templateKey", "support_followup_v1")).bearerAuth(analyst), Map.class);
        assertEquals(202, res.code());
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'inbox_followup'"));
        assertEquals(1L, data.countAudit("inbox.template"));
    }

    @Test void done_hides_the_thread_until_the_customer_writes_again() {
        var analyst = data.loginAsAnalyst(client);
        client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/status", Map.of("status", "done")).bearerAuth(analyst));
        assertTrue(client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class)).isEmpty());
        assertEquals(1, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?status=done").bearerAuth(analyst), Argument.listOf(Map.class)).size());

        db.inTx(c -> { WaInboxLog.inbound(c, mary, "wamid.in2", "text", "Hello again", null, null, null, null, Instant.now()); return null; });
        var open = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(1, open.size());
        assertEquals("Hello again", open.getFirst().get("lastPreview"));
    }

    @Test void saved_answers_are_listed_edited_and_audited() {
        var analyst = data.loginAsAnalyst(client);
        // (V50's seeded answers are gone here: cleanAdminTables truncates operators CASCADE.)
        var made = client.toBlocking().retrieve(HttpRequest.POST("/api/inbox/quick-replies",
                Map.of("title", "Gift wrap", "body", "Yes {name}, we can gift wrap it.")).bearerAuth(analyst), Map.class);
        var id = ((Number) made.get("id")).longValue();
        var edited = client.toBlocking().retrieve(HttpRequest.PUT("/api/inbox/quick-replies/" + id,
                Map.of("title", "Gift wrap", "body", "Yes {name}, gift wrap is free.", "sort", 5)).bearerAuth(analyst), Map.class);
        assertEquals("Yes {name}, gift wrap is free.", edited.get("body"));
        var tooLong = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(HttpRequest.POST(
                "/api/inbox/quick-replies", Map.of("title", "x".repeat(41), "body", "b")).bearerAuth(analyst), Map.class));
        assertEquals(400, tooLong.getStatus().getCode());
        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/quick-replies").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals("Gift wrap", list.getFirst().get("title"), "sorted by sort, then title");
        client.toBlocking().exchange(HttpRequest.DELETE("/api/inbox/quick-replies/" + id).bearerAuth(analyst));
        assertEquals(1L, data.countAudit("inbox.quick_reply.create"));
        assertEquals(1L, data.countAudit("inbox.quick_reply.update"));
        assertEquals(1L, data.countAudit("inbox.quick_reply.delete"));
    }

    @Test void alerts_register_only_browser_push_services() {
        var analyst = data.loginAsAnalyst(client);
        var key = client.toBlocking().retrieve(HttpRequest.GET("/api/staff-push/key").bearerAuth(analyst), Map.class);
        assertEquals(87, ((String) key.get("publicKey")).length(), "a 65-byte P-256 point, base64url");

        var endpoint = "https://fcm.googleapis.com/fcm/send/abc:def";
        client.toBlocking().exchange(HttpRequest.POST("/api/staff-push/subscribe", Map.of("endpoint", endpoint)).bearerAuth(analyst));
        var status = client.toBlocking().retrieve(HttpRequest.POST("/api/staff-push/status", Map.of("endpoint", endpoint)).bearerAuth(analyst), Map.class);
        assertEquals(Boolean.TRUE, status.get("on"));

        for (var bad : List.of("http://fcm.googleapis.com/x", "https://evil.example/fcm.googleapis.com", "https://169.254.169.254/latest",
                "https://fcm.googleapis.com:8443/x")) {
            var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                    HttpRequest.POST("/api/staff-push/subscribe", Map.of("endpoint", bad)).bearerAuth(analyst)), bad);
            assertEquals(400, e.getStatus().getCode(), bad);
        }

        client.toBlocking().exchange(HttpRequest.POST("/api/staff-push/unsubscribe", Map.of("endpoint", endpoint)).bearerAuth(analyst));
        assertEquals("0", q("SELECT count(*) FROM staff_push_subscriptions"));
    }
}
