package in.brand.engage.admin.halt;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.admin.auth.LoginRateLimit;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.BlockReason;
import in.brand.engage.policy.Decision;
import in.brand.engage.policy.DecisionRequest;
import in.brand.engage.policy.PolicyEngine;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T02 kill switches, and the phase-6 acceptance criterion against the real policy engine. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class HaltTest {

    static final String PUSH_MKT = "halt_test_push_mkt";
    static final String PUSH_UTIL = "halt_test_push_util";

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject PolicyEngine policy;
    @Inject Db db;

    @BeforeEach void reset() {
        data.cleanAdminTables();          // operators CASCADE: config_versions and campaigns too
        data.truncateCustomerTables();
        loginLimit.reset();
        template(PUSH_MKT, "marketing");
        template(PUSH_UTIL, "utility");
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> halt(String token, String scope, String selector, String reason) {
        var body = new java.util.HashMap<String, Object>();
        body.put("scope", scope);
        body.put("selector", selector);
        body.put("reason", reason);
        return client.toBlocking().retrieve(HttpRequest.POST("/api/halt", body).bearerAuth(token), Map.class);
    }

    void release(String token, String scope, String selector) {
        client.toBlocking().exchange(HttpRequest.DELETE("/api/halt/" + scope + "/" + selector + "?reason=incident%20over")
                .bearerAuth(token));
    }

    @Test void halting_marketing_blocks_the_next_marketing_decision_within_two_seconds_and_utility_continues() {
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        var id = pushSubscriber();
        assertNotEquals(BlockReason.MARKETING_HALTED, reason(policy.decide(DecisionRequest.of(id, Channel.PUSH, PUSH_MKT))));

        halt(sender, "marketing", "*", "Wrong price in the Diwali push");
        var halted = Instant.now();

        Decision marketing;
        do {
            marketing = policy.decide(DecisionRequest.of(id, Channel.PUSH, PUSH_MKT));
        } while (reason(marketing) != BlockReason.MARKETING_HALTED
                && Duration.between(halted, Instant.now()).toMillis() < 2_000);
        assertEquals(BlockReason.MARKETING_HALTED, reason(marketing), "within 2 s of the halt");
        assertInstanceOf(Decision.Allow.class, policy.decide(DecisionRequest.of(id, Channel.PUSH, PUSH_UTIL)),
                "utility is not stopped by a marketing halt");
        assertEquals(1L, data.countAudit("halt.set"));
    }

    @Test void only_campaign_senders_and_config_admins_halt_and_only_config_admins_release() {
        var viewer = data.loginAsViewer(client);
        var editor = data.loginAsCampaignEditor(client);
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        var admin = data.loginWithMfa(client, "admin@example.com", "CONFIG_ADMIN");

        assertEquals(403, status(() -> halt(viewer, "marketing", "*", "x")));
        assertEquals(403, status(() -> halt(editor, "marketing", "*", "x")));
        assertEquals(200, status(() -> halt(admin, "channel", "whatsapp", "provider outage")));
        assertEquals(200, status(() -> halt(sender, "marketing", "*", "bad copy")));

        assertEquals(403, status(() -> release(sender, "marketing", "*")));
        assertEquals(200, status(() -> release(admin, "marketing", "*")));

        var id = pushSubscriber();
        assertNotEquals(BlockReason.MARKETING_HALTED, reason(policy.decide(DecisionRequest.of(id, Channel.PUSH, PUSH_MKT))),
                "released");
        assertEquals(1L, data.countAudit("halt.release"));
    }

    @Test void the_console_lists_every_halt_in_force_with_who_and_why() {
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        halt(sender, "channel", "whatsapp", "Meta outage");
        halt(sender, "journey", "cart_abandon", "Wrong discount in step 2");

        var halts = (List<Map<String, Object>>) (List<?>) client.toBlocking().retrieve(
                HttpRequest.GET("/api/halt").bearerAuth(data.loginAsViewer(client)), Argument.listOf(Map.class));
        assertEquals(2, halts.size());
        var wa = halts.stream().filter(h -> "channel".equals(h.get("scope"))).findFirst().orElseThrow();
        assertEquals("whatsapp", wa.get("selector"));
        assertEquals("sender@example.com", wa.get("byEmail"));
        assertEquals("Meta outage", wa.get("reason"));
        assertNotNull(wa.get("since"));
    }

    @Test void halting_twice_writes_one_version_and_reports_it_was_already_halted() {
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        assertEquals(false, halt(sender, "marketing", null, "first").get("alreadyHalted"));
        assertEquals(true, halt(sender, "marketing", "*", "second").get("alreadyHalted"));
        assertEquals(1L, count("SELECT count(*) FROM config_versions WHERE key = 'halt.marketing'"));
        assertEquals(1L, data.countAudit("halt.set"));
    }

    @Test void a_halt_pauses_running_and_scheduled_campaigns_on_its_channel_and_release_leaves_them_paused() {
        var owner = data.createOperator("owner@example.com", "hunter2hunter2", "OWNER");
        var running = campaign(owner, "push", "RUNNING");
        var scheduled = campaign(owner, "push", "SCHEDULED");
        var draft = campaign(owner, "push", "DRAFT");
        var otherChannel = campaign(owner, "whatsapp", "RUNNING");
        var admin = data.loginWithMfa(client, "admin@example.com", "CONFIG_ADMIN");

        var result = halt(admin, "channel", "push", "FCM sending duplicates");

        assertEquals(List.of(running, scheduled).stream().sorted().toList(),
                ((List<String>) result.get("pausedCampaigns")).stream().sorted().toList());
        assertEquals("PAUSED", campaignStatus(running));
        assertEquals("PAUSED", campaignStatus(scheduled));
        assertEquals("DRAFT", campaignStatus(draft));
        assertEquals("RUNNING", campaignStatus(otherChannel));
        assertEquals(2L, data.countAudit("campaign.pause"));

        release(admin, "channel", "push");
        assertEquals("PAUSED", campaignStatus(running), "resuming is a deliberate campaign action");
    }

    @Test void a_halt_needs_a_reason_a_known_scope_and_a_valid_selector() {
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        assertEquals(400, status(() -> halt(sender, "marketing", "*", " ")));
        assertEquals(400, status(() -> halt(sender, "everything", "*", "x")));
        assertEquals(400, status(() -> halt(sender, "channel", "pigeon", "x")));
        assertEquals(400, status(() -> halt(sender, "marketing", "push", "x")), "marketing is global");
        assertEquals(400, status(() -> halt(sender, "journey", "Cart Abandon; DROP", "x")));
        assertEquals(0L, count("SELECT count(*) FROM config_versions"));
    }

    @Test void releasing_what_is_not_halted_is_404_and_a_release_needs_a_reason() {
        var admin = data.loginWithMfa(client, "admin@example.com", "CONFIG_ADMIN");
        assertEquals(404, status(() -> release(admin, "marketing", "*")));
        halt(admin, "marketing", "*", "x");
        assertEquals(400, status(() -> client.toBlocking().exchange(
                HttpRequest.DELETE("/api/halt/marketing/*").bearerAuth(admin))));
    }

    @Test void a_reason_with_quotes_is_stored_verbatim_in_the_audit_row() {
        var sender = data.loginWithMfa(client, "sender@example.com", "CAMPAIGN_SEND");
        halt(sender, "marketing", "*", "Copy said \"50% off\"");
        assertEquals("Copy said \"50% off\"", db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT after->>'reason' FROM audit_log WHERE action = 'halt.set'");
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }));
    }

    /* -------------------------------- fixtures -------------------------------- */

    static BlockReason reason(Decision d) {
        return d instanceof Decision.Block b ? b.reason() : null;
    }

    UUID pushSubscriber() {
        var id = UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source,
                                         consent_copy_ver, last_refreshed_at)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1', now())""",
                    id, "tok-" + UUID.randomUUID());
            for (var purpose : List.of("transactional", "marketing")) {
                Sql.update(c, """
                        INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                        VALUES (?, 'push', CAST(? AS purpose), 'granted', 'test', now() - interval '1 day')""",
                        id, purpose);
            }
            return null;
        });
        return id;
    }

    void template(String key, String category) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO templates (key, channel, category, status)
                VALUES (?, 'push', CAST(? AS msg_category), 'active')
                ON CONFLICT (key) DO UPDATE SET channel = 'push', category = EXCLUDED.category, status = 'active'""",
                key, category));
    }

    String campaign(UUID createdBy, String channel, String status) {
        var tpl = channel + "_halt_test_campaign";
        return db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, status)
                    VALUES (?, CAST(? AS channel), 'marketing', 'active') ON CONFLICT (key) DO NOTHING""", tpl, channel);
            try (var ps = Sql.prepare(c, """
                    INSERT INTO campaigns (name, channel, template_key, status, created_by)
                    VALUES ('test', CAST(? AS channel), ?, ?, ?) RETURNING id""", channel, tpl, status, createdBy);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    String campaignStatus(String id) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT status FROM campaigns WHERE id = CAST(? AS uuid)", id);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    long count(String sql) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
