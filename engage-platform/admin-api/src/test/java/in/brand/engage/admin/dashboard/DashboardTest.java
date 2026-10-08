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
        db.inTx(c -> Sql.update(c, "TRUNCATE spend_ledger, cascade_runs CASCADE"));
    }

    Map<String, Object> dashboard() {
        return client.toBlocking().retrieve(HttpRequest.GET("/api/dashboard").bearerAuth(data.loginAsViewer(client)), Map.class);
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
        var optOut = row(dash, "consentDaily", Map.of("channel", "whatsapp", "state", "withdrawn"));
        assertEquals("wa_stop_reply", optOut.get("source"));
        assertTrue(optOut.get("dayIst").toString().matches("\\d{4}-\\d{2}-\\d{2}"), "an ISO date");
        var journey = row(dash, "journeys", Map.of("intentKey", "cart_abandon"));
        assertEquals(1, ((Number) journey.get("stalled")).intValue(), "an hour overdue");
        assertEquals(1, ((Number) journey.get("entered24h")).intValue());
        assertFalse(dash.toString().contains("919800000001"), "no customer identifiers on the dashboard");
    }

    @Test void the_dashboard_needs_a_signed_in_operator() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/dashboard"), Map.class));
        assertEquals(401, e.getStatus().getCode());
    }
}
