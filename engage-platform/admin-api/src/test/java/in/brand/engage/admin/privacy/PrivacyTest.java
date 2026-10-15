package in.brand.engage.admin.privacy;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
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

/** P7-T05: erasure from the console and answering Shopify data requests. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings({"unchecked", "rawtypes"})
class PrivacyTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE privacy_requests, consent_erasure_proofs, pii_unmask_log, sends CASCADE"));
    }

    String owner() {
        return data.loginWithMfa(client, "owner@example.com", "OWNER");
    }

    String q(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    Map<String, Object> erase(String id, String reason, String token) {
        return client.toBlocking().retrieve(HttpRequest.POST("/api/customers/" + id + "/erase",
                reason == null ? Map.of() : Map.of("reason", reason)).bearerAuth(token), Map.class);
    }

    @Test void the_owner_erases_a_customer_and_it_is_recorded_without_saying_who() {
        var id = data.createIdentity("asha@example.com", "919812345678");
        db.inTx(c -> Sql.update(c, """
                INSERT INTO orders (id, order_number, identity_id, phone, email, total_paise, created_at)
                VALUES ('6600000000777', '#777', ?, '919812345678', 'asha@example.com', 99900, now())""", UUID.fromString(id)));

        var r = erase(id, "DPDP request by email, 15 Oct", owner());

        assertTrue(r.get("result").toString().contains("\"identities\": 1"), r.toString());
        assertNull(q("SELECT id::text FROM identities WHERE id = ?", UUID.fromString(id)));
        assertEquals("||99900", q("SELECT coalesce(phone,'') || '|' || coalesce(email,'') || '|' || total_paise FROM orders"));
        assertEquals("erase|done|console", q("SELECT kind || '|' || status || '|' || source FROM privacy_requests"));
        assertEquals("operator", q("SELECT a.actor_kind FROM audit_log a JOIN privacy_requests r ON a.entity_id = 'privacy_request:' || r.id WHERE a.action = 'privacy.erase'"));
        assertNull(q("SELECT 1 FROM privacy_requests WHERE result::text LIKE '%asha%' OR result::text LIKE ?", "%" + id + "%"));
    }

    @Test void erasing_needs_an_owner_and_a_reason() {
        var id = data.createIdentity("b@example.com", "919800000002");
        var analyst = assertThrows(HttpClientResponseException.class, () -> erase(id, "because they asked", data.loginAsAnalyst(client)));
        assertEquals(403, analyst.getStatus().getCode());
        var token = owner();
        var noReason = assertThrows(HttpClientResponseException.class, () -> erase(id, "", token));
        assertEquals(400, noReason.getStatus().getCode());
        var missing = assertThrows(HttpClientResponseException.class, () -> erase(UUID.randomUUID().toString(), "DPDP request", token));
        assertEquals(404, missing.getStatus().getCode());
        assertNotNull(q("SELECT id::text FROM identities WHERE id = ?", UUID.fromString(id)), "nothing erased");
    }

    @Test void a_data_request_report_lists_what_is_held_then_answering_forgets_who_asked() {
        var id = UUID.fromString(data.createIdentity("priya@example.com", "919876543210"));
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source) VALUES (?, 'whatsapp', 'marketing', 'granted', 'cart_attr')""", id);
            return Sql.update(c, """
                    INSERT INTO privacy_requests (kind, source, external_ref, subject)
                    VALUES ('data_request', 'shopify:d1', '9999', '{"email": "priya@example.com"}')""");
        });
        var token = owner();
        var list = (List<Map>) client.toBlocking().retrieve(HttpRequest.GET("/api/privacy-requests").bearerAuth(token), Argument.listOf(Map.class));
        assertEquals(1, list.size());
        assertEquals(true, list.getFirst().get("hasSubject"));
        assertFalse(list.toString().contains("priya@example.com"), "no identifiers in the list");
        var requestId = ((Number) list.getFirst().get("id")).longValue();

        var report = client.toBlocking().retrieve(HttpRequest.POST("/api/privacy-requests/" + requestId + "/report", Map.of())
                .bearerAuth(token), Map.class);
        var people = (List<Map>) report.get("people");
        assertEquals(1, people.size());
        assertTrue(people.getFirst().get("keys").toString().contains("919876543210"));
        assertEquals(1, ((List<?>) people.getFirst().get("consents")).size());
        assertEquals("data_request", q("SELECT field FROM pii_unmask_log"), "logged as a look at their details");

        client.toBlocking().retrieve(HttpRequest.POST("/api/privacy-requests/" + requestId + "/resolve",
                Map.of("note", "Sent to the store owner 15 Oct")).bearerAuth(token), Map.class);
        assertEquals("done|{}", q("SELECT status || '|' || subject::text FROM privacy_requests"));
        var again = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/privacy-requests/" + requestId + "/report", Map.of()).bearerAuth(token), Map.class));
        assertEquals(409, again.getStatus().getCode());
    }

    @Test void privacy_requests_are_for_the_owner() {
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/privacy-requests").bearerAuth(data.loginAsAnalyst(client)), List.class));
        assertEquals(403, e.getStatus().getCode());
    }
}
