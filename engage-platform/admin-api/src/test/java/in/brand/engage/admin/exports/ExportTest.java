package in.brand.engage.admin.exports;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T08 exports: throttled, audited, PII only for an OWNER, fetched through a short signed link. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class ExportTest {

    static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject ExportHousekeeping housekeeping;
    @Inject Db db;

    String analyst;
    UUID identity;
    String segment;

    @BeforeEach void reset() {
        data.cleanAdminTables();       // operators CASCADE: exports, export_files, segments, pii_unmask_log
        data.truncateCustomerTables(); // identities CASCADE: sends
        loginLimit.reset();
        analyst = data.loginAsAnalyst(client);
        identity = UUID.fromString(data.createIdentity("mary@example.com", "919800000055"));
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, intent_key, idempotency_key, status, decision)
                    VALUES (?, 'push', 'marketing', '=HYPERLINK("http://evil.example")', 'cart_recovery', 'exp-1', 'blocked',
                            '{"reason":"QUIET_HOURS"}')""", identity);
            try (var ps = Sql.prepare(c, """
                    INSERT INTO segments (name, definition) VALUES ('Kochi', '{"field":"city","op":"in","value":["kochi"]}')
                    RETURNING id"""); var rs = ps.executeQuery()) {
                rs.next();
                segment = rs.getString(1);
            }
            return null;
        });
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> export(String token, String kind, Map<String, Object> params, Object... more) {
        var body = new HashMap<String, Object>(Map.of("kind", kind, "params", params));
        for (int i = 0; i < more.length; i += 2) body.put((String) more[i], more[i + 1]);
        return client.toBlocking().retrieve(HttpRequest.POST("/api/exports", body).bearerAuth(token), Map.class);
    }

    String download(String token, Object exportId) {
        var link = client.toBlocking().retrieve(HttpRequest.POST("/api/exports/" + exportId + "/link", Map.of())
                .bearerAuth(token), Map.class);
        return client.toBlocking().retrieve(HttpRequest.GET((String) link.get("url")), String.class);   // no bearer
    }

    @Test void a_sends_export_downloads_through_a_signed_link_with_formulas_neutralised() {
        var created = export(analyst, "sends", Map.of("from", TODAY, "to", TODAY));
        assertEquals(1, ((Number) created.get("rowCount")).intValue());
        assertEquals("ready", created.get("status"));

        var csv = download(analyst, created.get("id"));
        assertTrue(csv.startsWith("id,identity_id,channel,"), csv);
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil.example\"\")\""), "a formula is text, not a formula: " + csv);
        assertTrue(csv.contains("QUIET_HOURS"));
        assertFalse(csv.contains("919800000055"), "no contact details in a sends export");
        assertEquals(1L, data.countAudit("export.create"));
        assertEquals(1L, data.countAudit("export.download"));
    }

    @Test void three_exports_a_day_is_the_limit() {
        for (int i = 0; i < 3; i++) export(analyst, "sends", Map.of("from", TODAY, "to", TODAY));
        assertEquals(429, status(() -> export(analyst, "sends", Map.of("from", TODAY, "to", TODAY))));
    }

    @Test void contact_details_need_an_owner_and_a_reason_and_are_logged() {
        var params = Map.<String, Object>of("segmentId", segment);
        assertEquals(403, status(() -> export(analyst, "segment_ids", params, "includePii", true, "reason", "Courier list")));

        var plain = download(analyst, export(analyst, "segment_ids", params).get("id"));
        assertTrue(plain.contains(identity.toString()));
        assertFalse(plain.contains("919800000055"));

        var owner = data.loginWithMfa(client, "owner@example.com", "OWNER");
        assertEquals(400, status(() -> export(owner, "segment_ids", params, "includePii", true)), "a reason is required");
        var withPii = download(owner, export(owner, "segment_ids", params, "includePii", true,
                "reason", "Courier escalation list for Kochi").get("id"));
        assertTrue(withPii.contains("919800000055"));
        assertTrue(withPii.contains("mary@example.com"));
        assertEquals(1L, count("SELECT count(*) FROM pii_unmask_log WHERE reason LIKE 'export %'"));
    }

    @Test void only_the_requester_can_mint_a_link_and_a_bad_or_expired_link_fails() {
        var created = export(analyst, "sends", Map.of("from", TODAY, "to", TODAY));
        var other = data.loginWithMfa(client, "owner@example.com", "OWNER");
        assertEquals(404, status(() -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/exports/" + created.get("id") + "/link", Map.of()).bearerAuth(other), Map.class)));
        assertEquals(401, status(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/exports/download?token=not-a-token"), String.class)));

        var link = client.toBlocking().retrieve(HttpRequest.POST("/api/exports/" + created.get("id") + "/link", Map.of())
                .bearerAuth(analyst), Map.class);
        db.inTx(c -> Sql.update(c, "UPDATE exports SET expires_at = now() - interval '1 minute'"));
        assertEquals(1, housekeeping.purge());
        assertEquals(0L, count("SELECT count(*) FROM export_files"), "the file is gone");
        assertEquals(404, status(() -> client.toBlocking().retrieve(HttpRequest.GET((String) link.get("url")), String.class)));
    }

    @Test void a_bad_request_is_refused_before_anything_is_written() {
        assertEquals(400, status(() -> export(analyst, "everything", Map.of())));
        assertEquals(400, status(() -> export(analyst, "sends", Map.of("from", "2026-01-01", "to", "2026-03-01"))), "31 days at most");
        assertEquals(400, status(() -> export(analyst, "campaign_report", Map.of("campaignId", UUID.randomUUID().toString()))));
        assertEquals(403, status(() -> export(data.loginAsViewer(client), "sends", Map.of("from", TODAY, "to", TODAY))));
        assertEquals(0L, count("SELECT count(*) FROM exports"));
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
