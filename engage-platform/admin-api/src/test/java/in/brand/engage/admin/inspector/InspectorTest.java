package in.brand.engage.admin.inspector;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.admin.auth.LoginRateLimit;
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

/** P6-T04 journey inspector: exact phone lookup, masked, logged, limited. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class InspectorTest {

    static final String PHONE = "919800000077";

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject Db db;

    String analyst;
    UUID identity;

    @BeforeEach void reset() {
        data.cleanAdminTables();       // operators CASCADE: pii_unmask_log too
        data.truncateCustomerTables(); // identities CASCADE: cascade_runs, sends
        loginLimit.reset();
        analyst = data.loginAsAnalyst(client);
        identity = UUID.fromString(data.createIdentity("mary@example.com", PHONE));
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> inspect(String token, String phone, String reason) {
        var body = new java.util.HashMap<String, Object>();
        body.put("phone", phone);
        body.put("reason", reason);
        return client.toBlocking().retrieve(HttpRequest.POST("/api/inspector", body).bearerAuth(token), Map.class);
    }

    long logged(String sql) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test void a_lookup_shows_every_run_attempt_and_decision_with_identifiers_masked() {
        db.inTx(c -> {
            long run;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status, outcome)
                    VALUES ('cart_abandon', ?, 'cart-9', 3, 'exhausted', 'no_channel') RETURNING id""", identity);
                 var rs = ps.executeQuery()) {
                rs.next();
                run = rs.getLong(1);
            }
            long send;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status,
                                       decision, cascade_run_id)
                    VALUES (?, 'push', 'marketing', 'cart_push_1', 'cart_abandon', 'insp-1', 'blocked',
                            '{"reason":"QUIET_HOURS"}', ?) RETURNING id""", identity, run);
                 var rs = ps.executeQuery()) {
                rs.next();
                send = rs.getLong(1);
            }
            Sql.update(c, """
                    INSERT INTO cascade_attempts (run_id, step_index, channel, send_id, result, reason)
                    VALUES (?, 0, 'push', ?, 'blocked', 'QUIET_HOURS'), (?, 1, 'whatsapp', NULL, 'skipped', 'NO_CONSENT')""",
                    run, send, run);
            return null;
        });

        var out = inspect(analyst, "+91 98000 00077", "Complaint: got no cart reminder");

        assertEquals(identity.toString(), out.get("identityId"));
        assertEquals("+91 98•••••077", out.get("phone"));
        assertEquals(List.of("m••••@example.com"), out.get("emails"));
        var run = ((List<Map<String, Object>>) out.get("runs")).getFirst();
        assertEquals("cart_abandon", run.get("intentKey"));
        var attempts = (List<Map<String, Object>>) run.get("attempts");
        assertEquals(List.of("QUIET_HOURS", "NO_CONSENT"), attempts.stream().map(a -> a.get("reason")).toList());
        var sent = ((List<Map<String, Object>>) out.get("sends")).getFirst();
        assertEquals(Map.of("reason", "QUIET_HOURS"), sent.get("decision"), "the decision as JSON, not text");
        assertFalse(out.toString().contains(PHONE), "never the full number");
        assertFalse(out.toString().contains("mary@"), "never the full email");

        assertEquals(1L, logged("SELECT count(*) FROM pii_unmask_log WHERE field = 'phone' "
                + "AND reason = 'Complaint: got no cart reminder' AND identity_id IS NOT NULL"));
    }

    @Test void a_miss_is_404_and_still_logged() {
        assertEquals(404, status(() -> inspect(analyst, "9811111111", "Checking a complaint")));
        assertEquals(1L, logged("SELECT count(*) FROM pii_unmask_log WHERE identity_id IS NULL"));
    }

    @Test void the_inspector_needs_an_analyst_a_reason_and_a_mobile_number() {
        var viewer = data.loginAsViewer(client);
        assertEquals(403, status(() -> inspect(viewer, PHONE, "Complaint #42")));
        assertEquals(400, status(() -> inspect(analyst, PHONE, " ")));
        assertEquals(400, status(() -> inspect(analyst, "0471 2345678", "Complaint #42")), "a landline");
        assertEquals(0L, logged("SELECT count(*) FROM pii_unmask_log"), "refused lookups are not lookups");
    }

    @Test void fifty_lookups_a_day_is_the_limit() {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO pii_unmask_log (operator_id, field, reason)
                SELECT id, 'phone', 'earlier' FROM operators, generate_series(1, 50)
                 WHERE email = 'analyst@example.com'"""));
        assertEquals(429, status(() -> inspect(analyst, PHONE, "Complaint #51")));
    }
}
