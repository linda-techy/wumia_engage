package in.brand.engage.admin.campaigns;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.admin.auth.LoginRateLimit;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T06 campaign lifecycle through the API, with the real dry run. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class CampaignTest {

    static final String PUSH_TPL = "push_campaign_new_arrivals_v1";
    static final String WA_OK = "wa_campaign_ok";
    static final String WA_PENDING = "wa_campaign_pending";
    static final String WA_RED = "wa_campaign_red";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject ConfigResolver config;
    @Inject Db db;

    String lead;        // CAMPAIGN_EDIT + CAMPAIGN_SEND: writes and could send, but never approves their own
    String sender;      // CAMPAIGN_SEND: the second pair of eyes
    String segment;     // state = KL

    @BeforeEach void reset() {
        data.cleanAdminTables();       // operators CASCADE: config_versions, segments, campaigns
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE channel_capability CASCADE"));
        loginLimit.reset();
        lead = data.loginWithMfa(client, "lead@example.com", "CAMPAIGN_EDIT");
        db.inTx(c -> Sql.update(c, """
                INSERT INTO operator_roles (operator_id, role) SELECT id, 'CAMPAIGN_SEND' FROM operators WHERE email = 'lead@example.com'"""));
        sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        var owner = data.createOperator("owner@example.com", "hunter2hunter2", "OWNER");
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.global_pct', '*', '0', ?, 'test: no random holdout')""", owner);
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, status) VALUES
                      (?, 'push', 'marketing', 'active'), (?, 'whatsapp', 'marketing', 'active'),
                      (?, 'whatsapp', 'marketing', 'active'), (?, 'whatsapp', 'marketing', 'active')
                    ON CONFLICT (key) DO UPDATE SET status = 'active'""", PUSH_TPL, WA_OK, WA_PENDING, WA_RED);
            Sql.update(c, "DELETE FROM wa_templates WHERE key LIKE 'wa\\_campaign\\_%'");
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES (?, 'en', 'ok', 'marketing', 'marketing', 'APPROVED', 'GREEN'),
                           (?, 'en', 'pending', 'marketing', NULL, 'PENDING', 'UNKNOWN'),
                           (?, 'en', 'red', 'marketing', 'marketing', 'APPROVED', 'RED')""", WA_OK, WA_PENDING, WA_RED);
            try (var ps = Sql.prepare(c, """
                    INSERT INTO segments (name, definition, created_by)
                    VALUES ('Kerala', '{"field":"state","op":"in","value":["KL"]}', ?) RETURNING id""", owner);
                 var rs = ps.executeQuery()) {
                rs.next();
                segment = rs.getString(1);
            }
            return null;
        });
        config.invalidate();
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> create(String channel, String template, Object... more) {
        var body = new HashMap<String, Object>();
        body.put("name", "Test campaign");
        body.put("channel", channel);
        body.put("templateKey", template);
        body.put("segmentId", segment);
        body.put("vars", Map.of("collection", "Linen", "url", "https://w.example/c/linen"));
        for (int i = 0; i < more.length; i += 2) body.put((String) more[i], more[i + 1]);
        return client.toBlocking().retrieve(HttpRequest.POST("/api/campaigns", body).bearerAuth(lead), Map.class);
    }

    Map<String, Object> act(String token, Object id, String action) {
        return client.toBlocking().retrieve(HttpRequest.POST("/api/campaigns/" + id + "/" + action, Map.of())
                .bearerAuth(token), Map.class);
    }

    @Test void a_push_dry_run_shows_stale_tokens_as_their_own_bucket() {
        var fresh1 = person("KL");
        device(fresh1, 1);
        grant(fresh1, "push");
        var fresh2 = person("KL");
        device(fresh2, 2);
        grant(fresh2, "push");
        var stale = person("KL");
        device(stale, 45);                                 // no refresh in 45 days
        grant(stale, "push");
        var noOptIn = person("KL");
        device(noOptIn, 1);
        person("MH");                                      // outside the segment

        var id = create("push", PUSH_TPL).get("id");
        var est = act(lead, id, "estimate");
        var breakdown = (Map<String, Object>) est.get("estimate");

        assertEquals(4, ((Number) breakdown.get("audience")).intValue());
        assertEquals(Map.of("STALE_TOKENS", 1), num((Map<String, Object>) breakdown.get("excluded")));
        var reached = ((Number) breakdown.get("willReceive")).intValue()
                + num((Map<String, Object>) breakdown.get("deferred")).values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(2, reached, "the two fresh, opted-in subscribers (deferred if run in quiet hours)");
        assertEquals(1, num((Map<String, Object>) breakdown.get("blocked")).values().stream().mapToInt(Integer::intValue).sum(),
                "no opt-in is a policy block, shown plainly: " + breakdown.get("blocked"));
        assertEquals("READY", est.get("status"), "push costs nothing: no approval needed");
        assertEquals(1L, data.countAudit("campaign.estimate"));
    }

    @Test void a_whatsapp_campaign_needs_another_operators_approval_and_never_reaches_unknown_capability() {
        var capable = person("KL");
        phone(capable);
        capability(capable, "CAPABLE");
        grant(capable, "whatsapp");
        var unknown = person("KL");
        phone(unknown);
        grant(unknown, "whatsapp");
        var incapable = person("KL");
        phone(incapable);
        capability(incapable, "INCAPABLE");

        var id = create("whatsapp", WA_OK, "budgetCapPaise", 50000).get("id");
        var est = act(lead, id, "estimate");
        assertEquals("PENDING_APPROVAL", est.get("status"));
        var excluded = num((Map<String, Object>) ((Map<String, Object>) est.get("estimate")).get("excluded"));
        assertEquals(Map.of("WA_CAPABILITY_UNKNOWN", 1, "WA_INCAPABLE", 1), excluded);

        assertEquals(409, status(() -> act(lead, id, "start")), "not approved yet");
        assertEquals(409, status(() -> act(lead, id, "approve")), "the author cannot approve their own campaign");
        assertEquals("READY", act(sender, id, "approve").get("status"));

        var started = act(lead, id, "start");
        assertEquals("RUNNING", started.get("status"));
        assertEquals(Set.of(capable), recipients(id), "no UNKNOWN or INCAPABLE identity is frozen in");
        assertNotNull(started.get("configSnapshotId"));
        assertEquals(1L, data.countAudit("campaign.approve"));
        assertEquals(1L, data.countAudit("campaign.start"));
    }

    @Test void the_whatsapp_picker_offers_only_approved_marketing_templates_and_the_server_enforces_it() {
        var picker = client.toBlocking().retrieve(HttpRequest.GET("/api/campaigns/templates?channel=whatsapp")
                .bearerAuth(lead), Argument.listOf(Map.class));
        var keys = picker.stream().map(t -> t.get("key")).toList();
        assertTrue(keys.contains(WA_OK));
        assertFalse(keys.contains(WA_PENDING));
        assertFalse(keys.contains(WA_RED), "quality RED");

        assertEquals(400, status(() -> create("whatsapp", WA_PENDING, "budgetCapPaise", 50000)));
        assertEquals(400, status(() -> create("whatsapp", WA_RED, "budgetCapPaise", 50000)));
        assertEquals(400, status(() -> create("whatsapp", WA_OK)), "every WhatsApp send is paid: a budget cap is required");
        assertEquals(400, status(() -> create("push", WA_OK)), "a WhatsApp template on push");
    }

    @Test void editing_an_estimated_campaign_sends_it_back_to_draft() {
        var created = create("push", PUSH_TPL);
        var id = created.get("id");
        act(lead, id, "estimate");
        var body = new HashMap<String, Object>(Map.of("name", "Renamed", "channel", "push", "templateKey", PUSH_TPL,
                "segmentId", segment));
        var edited = client.toBlocking().retrieve(HttpRequest.PATCH("/api/campaigns/" + id, body).bearerAuth(lead), Map.class);
        assertEquals("DRAFT", edited.get("status"));
        assertNull(edited.get("estimate"));
        assertEquals(409, status(() -> act(lead, id, "start")), "estimate again first");
    }

    @Test void a_halt_blocks_the_start_and_a_running_campaign_pauses_resumes_and_cancels() {
        var id = create("push", PUSH_TPL).get("id");
        act(lead, id, "estimate");
        db.inTx(c -> Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                SELECT 'halt.marketing', '*', 'true', id, 'test halt' FROM operators WHERE email = 'owner@example.com'"""));
        assertEquals(409, status(() -> act(lead, id, "start")));
        db.inTx(c -> Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                SELECT 'halt.marketing', '*', 'false', id, 'test release' FROM operators WHERE email = 'owner@example.com'"""));

        assertEquals("RUNNING", act(sender, id, "start").get("status"));
        assertEquals("PAUSED", act(sender, id, "pause").get("status"));
        assertEquals("RUNNING", act(sender, id, "resume").get("status"));
        assertEquals("CANCELLED", act(sender, id, "cancel").get("status"));
        assertEquals(409, status(() -> act(sender, id, "resume")));
        var viewer = data.loginAsViewer(client);
        assertEquals(403, status(() -> act(viewer, id, "pause")));
    }

    /* -------------------------------- fixtures -------------------------------- */

    static Map<String, Integer> num(Map<String, Object> m) {
        var out = new HashMap<String, Integer>();
        m.forEach((k, v) -> out.put(k, ((Number) v).intValue()));
        return out;
    }

    Set<UUID> recipients(Object campaignId) {
        return db.inTx(c -> {
            var out = new java.util.HashSet<UUID>();
            try (var ps = Sql.prepare(c, "SELECT identity_id FROM campaign_recipients WHERE campaign_id = CAST(? AS uuid)",
                    campaignId.toString()); var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class));
            }
            return out;
        });
    }

    UUID person(String state) {
        var id = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, jsonb_build_object('state', ?::text))", id, state);
            return null;
        });
        return id;
    }

    void phone(UUID id) {
        db.inTx(c -> Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, true)",
                id, "9197" + String.format("%08d", SEQ.incrementAndGet())));
    }

    void device(UUID id, int daysSinceRefresh) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver,
                                     last_refreshed_at)
                VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1', now() - make_interval(days => ?))""",
                id, "tok-" + UUID.randomUUID(), daysSinceRefresh));
    }

    void grant(UUID id, String channel) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), 'marketing', 'granted', 'test', now() - interval '2 days')""", id, channel));
    }

    void capability(UUID id, String state) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel, state, recheck_after)
                VALUES (?, 'whatsapp', ?, CASE WHEN ? = 'INCAPABLE' THEN now() + interval '30 days' END)""", id, state, state));
    }
}
