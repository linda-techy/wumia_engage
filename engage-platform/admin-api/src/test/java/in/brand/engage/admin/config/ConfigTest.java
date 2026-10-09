package in.brand.engage.admin.config;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.admin.auth.LoginRateLimit;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T03: config writes, four-eyes proposals, effective dating, snapshots. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class ConfigTest {

    static final String WA_CAP = "cap.whatsapp.marketing.1d";     // CRITICAL, 0..2
    static final String PUSH_CAP = "cap.push.marketing.1d";       // GUARDED, 0..10

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject Db db;

    String admin;

    @BeforeEach void reset() {
        data.cleanAdminTables();       // operators CASCADE: config_versions and config_proposals too
        loginLimit.reset();
        admin = data.loginWithMfa(client, "admin@example.com", "CONFIG_ADMIN");
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> write(String token, String key, Object value, String reason, Object... more) {
        var body = new HashMap<String, Object>();
        body.put("selector", "*");
        body.put("value", value);
        body.put("reason", reason);
        for (int i = 0; i < more.length; i += 2) body.put((String) more[i], more[i + 1]);
        return client.toBlocking().retrieve(HttpRequest.POST("/api/config/" + key, body).bearerAuth(token), Map.class);
    }

    Map<String, Object> decide(String token, Object proposalId, String action) {
        return client.toBlocking().retrieve(HttpRequest.POST("/api/config/proposals/" + proposalId + "/" + action, Map.of())
                .bearerAuth(token), Map.class);
    }

    Map<String, Object> keyRow(String token, String key) {
        var all = client.toBlocking().retrieve(HttpRequest.GET("/api/config").bearerAuth(token), Argument.listOf(Map.class));
        return (Map<String, Object>) all.stream().filter(r -> key.equals(r.get("key"))).findFirst().orElseThrow();
    }

    /** What config_current resolves for (key, '*') right now, as JSON text; null when only the default applies. */
    String inForce(String key) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT value::text FROM config_current WHERE key = ? AND selector = '*'", key);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    static String inSeconds(int s) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(s).toString();
    }

    @Test void the_registry_lists_every_key_with_its_schema_default_and_versions() {
        var wa = keyRow(data.loginAsViewer(client), WA_CAP);
        assertEquals("CRITICAL", wa.get("risk"));
        assertEquals("INT", wa.get("valueType"));
        assertEquals(2, ((Number) ((Map<String, Object>) wa.get("jsonSchema")).get("maximum")).intValue(),
                "Meta's per-user limit");
        assertEquals(1, ((Number) wa.get("defaultValue")).intValue());
        assertEquals(List.of(), wa.get("inForce"));
        assertEquals(List.of(), wa.get("pending"));
    }

    @Test void a_guarded_change_takes_effect_at_once_with_its_actor_and_reason() {
        var created = write(admin, PUSH_CAP, 2, "Two pushes a day during the EOSS");
        assertNotNull(created.get("versionId"));

        assertEquals("2", inForce(PUSH_CAP));
        var row = (Map<String, Object>) ((List<?>) keyRow(admin, PUSH_CAP).get("inForce")).getFirst();
        assertEquals("admin@example.com", row.get("changedBy"));
        assertEquals("Two pushes a day during the EOSS", row.get("reason"));
        assertEquals(1L, data.countAudit("config.update"));

        var history = client.toBlocking().retrieve(HttpRequest.GET("/api/config/" + PUSH_CAP + "/history")
                .bearerAuth(data.loginAsAnalyst(client)), Argument.listOf(Map.class));
        assertEquals(1, history.size());
    }

    @Test void a_write_needs_the_role_a_reason_and_a_valid_value() {
        var viewer = data.loginAsViewer(client);
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        var ok = "A reason long enough";

        assertEquals(403, status(() -> write(viewer, PUSH_CAP, 2, ok)));
        assertEquals(403, status(() -> write(sender, PUSH_CAP, 2, ok)));
        assertEquals(400, status(() -> write(admin, PUSH_CAP, 2, "too short")), "10+ characters");
        assertEquals(400, status(() -> write(admin, PUSH_CAP, 11, ok)), "schema maximum");
        assertEquals(400, status(() -> write(admin, PUSH_CAP, "2", ok)), "type");
        assertEquals(400, status(() -> write(admin, "quiet_hours", Map.of("from", "9pm", "to", "9am"), ok)));
        assertEquals(400, status(() -> write(admin, "holdout.global_pct", 5, ok, "selector", "whatsapp")), "GLOBAL key");
        assertEquals(400, status(() -> write(admin, PUSH_CAP, 2, ok, "effectiveFrom", inSeconds(-3600))), "no backdating");
        assertEquals(400, status(() -> write(admin, PUSH_CAP, 2, ok,
                "effectiveFrom", inSeconds(3600), "effectiveTo", inSeconds(60))));
        assertEquals(404, status(() -> write(admin, "cap.pigeon.marketing.1d", 2, ok)));
        assertEquals(409, status(() -> write(admin, "halt.marketing", true, ok)), "kill switches go through /api/halt");
        assertNull(inForce(PUSH_CAP));
        assertEquals(0L, data.countAudit("config.update"));
    }

    @Test void a_critical_change_waits_for_a_second_operator_and_self_approval_is_409() {
        var second = data.loginWithMfa(client, "second@example.com", "CONFIG_ADMIN");

        var proposed = write(admin, WA_CAP, 2, "Festive week: two WhatsApp offers a day");
        assertEquals("PENDING", proposed.get("status"));
        assertNull(inForce(WA_CAP), "nothing in config_versions until approved");
        assertEquals(1, ((List<?>) keyRow(admin, WA_CAP).get("pending")).size());
        assertEquals(409, status(() -> write(second, WA_CAP, 0, "A competing proposal for the same key")),
                "one pending proposal per setting");

        assertEquals(409, status(() -> decide(admin, proposed.get("proposalId"), "approve")), "four eyes");
        var approved = decide(second, proposed.get("proposalId"), "approve");
        assertEquals("APPROVED", approved.get("status"));
        assertEquals("2", inForce(WA_CAP));

        var row = (Map<String, Object>) ((List<?>) keyRow(admin, WA_CAP).get("inForce")).getFirst();
        assertEquals("admin@example.com", row.get("changedBy"));
        assertEquals("second@example.com", row.get("approvedBy"));
        assertEquals(409, status(() -> decide(second, proposed.get("proposalId"), "approve")), "already decided");
        assertEquals(1L, data.countAudit("config.propose"));
        assertEquals(1L, data.countAudit("config.approve"));
    }

    @Test void an_approved_future_dated_value_takes_effect_at_its_start_and_not_before() throws Exception {
        var second = data.loginWithMfa(client, "second@example.com", "CONFIG_ADMIN");
        var start = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3);
        var proposed = write(admin, "holdout.global_pct", 10, "Bigger holdout for the Q4 lift read",
                "effectiveFrom", start.toString());
        decide(second, proposed.get("proposalId"), "approve");

        assertNull(inForce("holdout.global_pct"), "not before its start");
        assertEquals(1, ((List<?>) keyRow(admin, "holdout.global_pct").get("scheduled")).size());

        Thread.sleep(Math.max(0, java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), start).toMillis()) + 500);
        assertEquals("10", inForce("holdout.global_pct"), "at its start, with no further write");
    }

    @Test void a_festive_cap_with_an_end_reverts_on_its_own() throws Exception {
        write(admin, PUSH_CAP, 3, "Normal daily push cap");
        var end = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3);
        write(admin, PUSH_CAP, 5, "Diwali week: five pushes a day", "effectiveTo", end.toString());
        assertEquals("5", inForce(PUSH_CAP));

        Thread.sleep(Math.max(0, java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), end).toMillis()) + 500);
        assertEquals("3", inForce(PUSH_CAP), "back to the normal cap, nobody had to remember");
    }

    @Test void a_proposal_is_rejected_by_another_operator_or_withdrawn_by_its_proposer() {
        var second = data.loginWithMfa(client, "second@example.com", "CONFIG_ADMIN");
        var first = write(admin, WA_CAP, 0, "Pause WhatsApp marketing for a week");
        assertEquals(409, status(() -> decide(admin, first.get("proposalId"), "reject")), "withdraw your own instead");
        assertEquals("REJECTED", decide(second, first.get("proposalId"), "reject").get("status"));

        var again = write(admin, WA_CAP, 0, "Pause WhatsApp marketing, take two");
        assertEquals(403, status(() -> client.toBlocking().exchange(
                HttpRequest.DELETE("/api/config/proposals/" + again.get("proposalId")).bearerAuth(second))));
        client.toBlocking().exchange(HttpRequest.DELETE("/api/config/proposals/" + again.get("proposalId")).bearerAuth(admin));
        assertEquals(List.of(), keyRow(admin, WA_CAP).get("pending"));
        assertNull(inForce(WA_CAP));
        assertEquals(1L, data.countAudit("config.reject"));
        assertEquals(1L, data.countAudit("config.withdraw"));
    }

    @Test void a_snapshot_shows_the_whole_config_a_send_was_decided_under() {
        long id = db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO config_snapshots (fingerprint, version_ids, resolved)
                    VALUES (?, '{1,2}', '{"cap.push.marketing.1d|*":"3"}') RETURNING id""",
                    java.util.UUID.randomUUID().toString().getBytes());
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
        var viewer = data.loginAsViewer(client);
        var snap = client.toBlocking().retrieve(HttpRequest.GET("/api/config/snapshots/" + id).bearerAuth(viewer), Map.class);
        assertEquals(List.of(1, 2), ((List<Number>) snap.get("versionIds")).stream().map(Number::intValue).toList());
        assertEquals("3", ((Map<String, Object>) snap.get("resolved")).get("cap.push.marketing.1d|*"));
        assertEquals(404, status(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/config/snapshots/999999999").bearerAuth(viewer), Map.class)));
    }
}
