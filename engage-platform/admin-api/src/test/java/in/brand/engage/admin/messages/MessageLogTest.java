package in.brand.engage.admin.messages;

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
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** V52: every send to every customer, newest first, masked, with filters and paging. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class MessageLogTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    UUID mary;
    UUID ravi;

    long send(UUID who, String channel, String template, String intent, String status, String reason) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status, decision, failed_reason, sent_at)
                    VALUES (?, CAST(? AS channel), 'marketing', ?, ?, ?, CAST(? AS send_status), CAST(? AS jsonb), ?, now()) RETURNING id""",
                    who, channel, template, intent, "log-" + UUID.randomUUID(), status,
                    "blocked".equals(status) ? "{\"reason\":\"" + reason + "\"}" : "{}", "failed".equals(status) ? reason : null);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        mary = UUID.fromString(data.createIdentity("mary@example.com", "918606572870"));
        ravi = UUID.fromString(data.createIdentity("ravi@example.com", "919876543210"));
        db.inTx(c -> {
            Sql.update(c, "TRUNCATE sends CASCADE");
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, title, body) VALUES ('push_cart_recovery_v1', 'push', 'marketing',
                      'Your bag is waiting, {{name}}', 'Complete your order before it sells out.')
                    ON CONFLICT (key) DO UPDATE SET title = EXCLUDED.title, body = EXCLUDED.body""");
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category) VALUES ('cart_reminder_v1', 'whatsapp', 'marketing')
                    ON CONFLICT (key) DO NOTHING""");
            return null;
        });
    }

    Map<String, Object> page(String token, String query) {
        return client.toBlocking().retrieve(HttpRequest.GET("/api/message-log" + query).bearerAuth(token), Map.class);
    }

    @Test void every_send_shows_who_what_and_what_happened_masked_newest_first() {
        send(mary, "push", "push_cart_recovery_v1", "cart_recovery", "clicked", null);
        long wa = send(ravi, "whatsapp", "cart_reminder_v1", "cart_recovery", "read", null);
        db.inTx(c -> {
            WaInboxLog.outbound(c, ravi, "wamid.log1", "template", "Hi Ravi, your Kundan Jhumka is waiting in your bag.", "cart_reminder_v1", wa, null, Instant.now());
            return null;
        });
        send(mary, "sms", "sms_order_shipped", "order_tracking", "failed", "dlt_pending");
        send(ravi, "push", "push_cart_recovery_v1", "cart_recovery", "blocked", "QUIET_HOURS");

        var viewer = data.loginAsViewer(client);
        var p = page(viewer, "");
        var rows = (List<Map<String, Object>>) p.get("rows");
        assertEquals(4, rows.size());
        assertEquals("blocked|QUIET_HOURS", rows.getFirst().get("status") + "|" + rows.getFirst().get("blockReason"));
        var waRow = rows.get(2);
        assertEquals("Hi Ravi, your Kundan Jhumka is waiting in your bag.", waRow.get("text"), "WhatsApp shows what went out");
        var push = rows.get(3);
        assertEquals("Your bag is waiting, {{name}}", push.get("templateTitle"), "push shows the template wording");
        assertEquals("Mary", push.get("name"));
        assertFalse(String.valueOf(rows).contains("8606572870") || String.valueOf(rows).contains("mary@example.com"), "masked");
        assertEquals(1, ((Number) ((Map<String, Object>) p.get("counts")).get("failed")).intValue());

        var onlyWa = (List<?>) page(viewer, "?channel=whatsapp").get("rows");
        assertEquals(1, onlyWa.size());
        var notSent = (List<?>) page(viewer, "?outcome=blocked").get("rows");
        assertEquals(1, notSent.size());
        var maryOnly = (List<Map<String, Object>>) page(viewer, "?q=mary@example.com").get("rows");
        assertEquals(2, maryOnly.size());
        assertEquals(Boolean.TRUE, page(viewer, "?q=nobody@example.com").get("customerNotFound"));
        assertEquals(List.of("cart_recovery", "order_tracking"),
                client.toBlocking().retrieve(HttpRequest.GET("/api/message-log/journeys").bearerAuth(viewer), Argument.listOf(String.class)));

        var bad = assertThrows(HttpClientResponseException.class, () -> page(viewer, "?channel=fax"));
        assertEquals(400, bad.getStatus().getCode());
    }

    @Test void pages_of_fifty_continue_from_the_last_id() {
        for (int i = 0; i < 53; i++) send(mary, "push", "push_cart_recovery_v1", "cart_recovery", "sent", null);
        var viewer = data.loginAsViewer(client);
        var first = page(viewer, "");
        var rows = (List<Map<String, Object>>) first.get("rows");
        assertEquals(50, rows.size());
        assertEquals(Boolean.TRUE, first.get("more"));
        var next = page(viewer, "?before=" + rows.getLast().get("id"));
        assertEquals(3, ((List<?>) next.get("rows")).size());
        assertEquals(Boolean.FALSE, next.get("more"));
    }
}
