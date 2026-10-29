package in.brand.engage.admin.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T02 dashboard: one read over the V16 views. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class DashboardTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE spend_ledger, cascade_runs, wa_phone_numbers, push_prompt_events CASCADE"));
    }

    Map<String, Object> dashboard() {
        return dashboard("");
    }

    Map<String, Object> dashboard(String query) {
        return client.toBlocking().retrieve(HttpRequest.GET("/api/dashboard" + query).bearerAuth(data.loginAsViewer(client)), Map.class);
    }

    static long n(Map<String, Object> dash, String key, String field) {
        return ((Number) ((Map<String, Object>) dash.get(key)).get(field)).longValue();
    }

    static List<Map<String, Object>> rows(Map<String, Object> dash, String key) {
        return (List<Map<String, Object>>) dash.get(key);
    }

    static Map<String, Object> row(Map<String, Object> dash, String key, Map<String, Object> match) {
        return rows(dash, key).stream()
                .filter(r -> match.entrySet().stream().allMatch(e -> e.getValue().equals(r.get(e.getKey()))))
                .findFirst().orElseThrow(() -> new AssertionError("no " + key + " row " + match + " in " + dash.get(key)));
    }

    @Test void spend_today_is_shown_against_the_budget_in_force() {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO spend_ledger (day, channel, category, messages, paise)
                VALUES ((now() AT TIME ZONE 'Asia/Kolkata')::date, 'whatsapp', 'marketing', 60, 51600),
                       ((now() AT TIME ZONE 'Asia/Kolkata')::date - 1, 'whatsapp', 'marketing', 999, 999999)"""));

        var wa = row(dashboard(), "spendToday", Map.of("channel", "whatsapp", "category", "marketing"));
        assertEquals(51600, ((Number) wa.get("spentPaise")).intValue(), "today only");
        assertEquals(60, ((Number) wa.get("messages")).intValue());
        assertEquals(200000, ((Number) wa.get("budgetPaise")).intValue(), "the V8 default");
    }

    @Test void block_reasons_capability_consent_and_journey_health_are_counted() {
        var id = UUID.fromString(data.createIdentity("a@example.com", "919800000001"));
        db.inTx(c -> {
            for (int i = 0; i < 3; i++) {
                Sql.update(c, """
                        INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, decision)
                        VALUES (?, 'push', 'marketing', 't', ?, 'blocked', '{"reason":"QUIET_HOURS"}')""",
                        id, "k" + i);
            }
            Sql.update(c, "INSERT INTO channel_capability (identity_id, channel, state) VALUES (?, 'whatsapp', 'UNKNOWN')", id);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source)
                    VALUES (?, 'whatsapp', 'marketing', 'withdrawn', 'wa_stop_reply')""", id);
            Sql.update(c, """
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status, next_step_at)
                    VALUES ('cart_abandon', ?, 'cart-1', 3, 'active', now() - interval '1 hour')""", id);
            return null;
        });

        var dash = dashboard();
        assertEquals(3, ((Number) row(dash, "sends24h", Map.of("status", "blocked", "reason", "QUIET_HOURS"))
                .get("sends")).intValue());
        assertEquals(1, ((Number) row(dash, "capability", Map.of("channel", "whatsapp", "state", "UNKNOWN"))
                .get("identities")).intValue());
        var optOut = row(dash, "optIns", Map.of("channel", "whatsapp"));
        assertEquals(1, ((Number) optOut.get("optedOut")).intValue());
        assertTrue(optOut.get("dayIst").toString().matches("\\d{4}-\\d{2}-\\d{2}"), "an ISO date");
        var journey = row(dash, "journeys", Map.of("intentKey", "cart_abandon"));
        assertEquals(1, ((Number) journey.get("stalled")).intValue(), "an hour overdue");
        assertEquals(1, ((Number) journey.get("entered24h")).intValue());
        assertFalse(dash.toString().contains("919800000001"), "no customer identifiers on the dashboard");
    }

    /** Revenue credited to messages this period against the one before, and against spend. */
    @Test void the_headline_is_revenue_from_messages_and_what_they_cost() {
        var id = UUID.fromString(data.createIdentity("b@example.com", "919800000002"));
        db.inTx(c -> {
            long send = send(c, id, "cart_recovery", "whatsapp", "marketing");
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o1', 'WUM1', ?, 149900, now())", id);
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o2', 'WUM2', ?, 50000, now() - interval '10 days')", id);
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o3', 'WUM3', ?, 99900, now())", id);
            Sql.update(c, "INSERT INTO conversions (identity_id, order_id, value_paise, last_send_id, attribution) VALUES (?, 'o1', 149900, ?, 'click')", id, send);
            Sql.update(c, """
                    INSERT INTO conversions (identity_id, order_id, value_paise, last_send_id, attribution, occurred_at)
                    VALUES (?, 'o2', 50000, ?, 'view', now() - interval '10 days')""", id, send);
            Sql.update(c, "INSERT INTO conversions (identity_id, order_id, value_paise) VALUES (?, 'o3', 99900)", id);
            Sql.update(c, """
                    INSERT INTO spend_ledger (day, channel, category, messages, paise)
                    VALUES ((now() AT TIME ZONE 'Asia/Kolkata')::date, 'whatsapp', 'marketing', 10, 8600)""");
            Sql.update(c, """
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status, next_step_at)
                    VALUES ('cart_recovery', ?, 'cart-9', 3, 'succeeded', now())""", id);
            return null;
        });

        var dash = dashboard("?days=7");
        assertEquals(7, ((Number) dash.get("days")).intValue());
        assertEquals(1, n(dash, "headline", "orders"), "an order with no message before it is not credited");
        assertEquals(149900, n(dash, "headline", "revenuePaise"));
        assertEquals(50000, n(dash, "headline", "prevRevenuePaise"), "10 days ago: the 7 days before");
        assertEquals(8600, n(dash, "headline", "spendPaise"));
        var cart = row(dash, "recovery", Map.of("journey", "cart_recovery"));
        assertEquals(1, ((Number) cart.get("started")).intValue());
        assertEquals(1, ((Number) cart.get("recovered")).intValue());
        assertEquals(149900, ((Number) cart.get("revenuePaise")).intValue());
        assertEquals(0, ((Number) row(dash, "recovery", Map.of("journey", "checkout_abandon")).get("started")).intValue(),
                "both journeys always listed");
    }

    @Test void audience_inbox_and_the_whatsapp_number_are_counted_in_people() {
        var a = UUID.fromString(data.createIdentity("c@example.com", "919800000003"));
        var b = UUID.fromString(data.createIdentity("d@example.com", "919800000004"));
        db.inTx(c -> {
            for (var id : List.of(a, b)) {
                Sql.update(c, "INSERT INTO consents (identity_id, channel, purpose, state, source) VALUES (?, 'whatsapp', 'marketing', 'granted', 'wa_offer_link')", id);
                Sql.update(c, "INSERT INTO consents (identity_id, channel, purpose, state, source) VALUES (?, 'whatsapp', 'transactional', 'granted', 'wa_offer_link')", id);
            }
            Sql.update(c, "INSERT INTO suppressions (identity_id, channel, reason) VALUES (?, 'whatsapp', 'user_blocked')", b);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, 'tok-1', 'WEB', 'https://www.wumika.com', 'browse', 'v1'),
                           (?, 'tok-2', 'WEB', 'https://www.wumika.com', 'browse', 'v1')""", a, a);
            Sql.update(c, """
                    INSERT INTO wa_threads (identity_id, last_message_at, last_inbound_at, unread, status)
                    VALUES (?, now() - interval '2 hours', now() - interval '2 hours', 1, 'open'),
                           (?, now(), now() - interval '1 hour', 0, 'open')""", a, b);
            Sql.update(c, """
                    INSERT INTO wa_phone_numbers (phone_number_id, display_number, quality, messaging_tier)
                    VALUES ('111', '+1 555-796-3126', 'GREEN', 'TIER_250'), ('222', '+91 98000 00000', 'YELLOW', 'TIER_1K')""");
            send(c, a, "cart_recovery", "whatsapp", "marketing");
            send(c, a, "order_shipped", "whatsapp", "utility");
            send(c, b, "inbox_staff_reply", "whatsapp", "service");
            for (var step : List.of("soft_shown", "soft_shown", "soft_accepted", "native_denied")) {
                Sql.update(c, "INSERT INTO push_prompt_events (anon_id, surface, step, reason) VALUES ('anon-1', 'browse', ?, ?)",
                        step, step.equals("native_denied") ? "auto" : null);
            }
            Sql.update(c, "INSERT INTO push_prompt_events (anon_id, surface, step) VALUES ('anon-2', 'wa_offer', 'soft_shown')");
            return null;
        });

        var dash = dashboard();
        assertEquals(1, n(dash, "audience", "whatsapp"), "a blocked number is not reachable");
        assertEquals(1, n(dash, "audience", "push"), "two devices, one person");
        assertEquals(1, n(dash, "inbox", "waiting"), "answered chats are not waiting");
        assertEquals(1, n(dash, "inbox", "unread"));
        var number = rows(dash, "waNumber");
        assertEquals(1, number.size(), "only the number Engage sends from (whatsapp.display_number)");
        assertEquals("TIER_250", number.getFirst().get("messagingTier"));
        assertEquals(1, ((Number) number.getFirst().get("used24h")).intValue(), "one person messaged first; chat replies do not count");
        assertEquals(1, ((Number) row(dash, "channels", Map.of("channel", "whatsapp_chat")).get("sent")).intValue());
        assertEquals(2, ((Number) row(dash, "channels", Map.of("channel", "whatsapp")).get("sent")).intValue());
        assertEquals(1, n(dash, "pushFunnel", "shown"), "people, not times shown");
        assertEquals(1, n(dash, "pushFunnel", "blockedBySetting"));
        assertEquals(1, n(dash, "waOfferFunnel", "shown"), "the WhatsApp card is not in the push funnel");
        assertEquals(2, n(dash, "waOfferFunnel", "joined"));
    }

    @Test void the_period_is_today_7_or_30_days() {
        var e = assertThrows(HttpClientResponseException.class, () -> dashboard("?days=3"));
        assertEquals(400, e.getStatus().getCode());
        assertEquals(30, ((Number) dashboard("?days=30").get("days")).intValue());
    }

    static long send(java.sql.Connection c, UUID id, String intent, String channel, String category) throws java.sql.SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, intent_key, status, sent_at)
                VALUES (?, CAST(? AS channel), CAST(? AS msg_category), 't', ?, ?, 'sent', now()) RETURNING id""",
                id, channel, category, UUID.randomUUID().toString(), intent); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test void the_dashboard_needs_a_signed_in_operator() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/dashboard"), Map.class));
        assertEquals(401, e.getStatus().getCode());
    }
}
