package in.brand.engage.admin.support;

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

/** Support requests in the admin console: list, reply text through PiiAccess, resolve. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class SupportTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    long requestId;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        var identity = UUID.fromString(data.createIdentity("mary@example.com", "918606572870"));
        requestId = db.inTx(c -> {
            Sql.update(c, "TRUNCATE support_requests");
            Sql.update(c, "INSERT INTO orders (id, order_number, identity_id, total_paise, created_at) VALUES ('o-1', '#1042', ?, 100, now())", identity);
            try (var ps = Sql.prepare(c, "INSERT INTO shipments (order_id, awb, carrier, status, status_at) VALUES ('o-1', 'AWB77', 'Delhivery', 'ndr', now()) RETURNING id");
                 var rs = ps.executeQuery()) {
                rs.next();
                long shipment = rs.getLong(1);
                try (var ps2 = Sql.prepare(c, """
                        INSERT INTO support_requests (identity_id, kind, order_id, shipment_id, message_text, message_ids)
                        VALUES (?, 'delivery', 'o-1', ?, 'Flat 4B, deliver after 5pm', ARRAY['m1']) RETURNING id""", identity, shipment);
                     var rs2 = ps2.executeQuery()) {
                    rs2.next();
                    return rs2.getLong(1);
                }
            }
        });
    }

    @Test void the_list_shows_the_order_and_parcel_but_not_the_reply() {
        var token = data.loginAsViewer(client);
        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/support-requests").bearerAuth(token), Argument.listOf(Map.class));
        assertEquals(1, list.size());
        var row = (Map<String, Object>) list.getFirst();
        assertEquals("#1042", row.get("orderNumber"));
        assertEquals("AWB77", row.get("awb"));
        assertEquals(Boolean.TRUE, row.get("hasText"));
        assertFalse(list.toString().contains("Flat 4B"), "the reply text is not in the list");
        assertFalse(list.toString().contains("8606572870"));
    }

    @Test void the_reply_text_needs_analyst_and_is_logged_as_a_pii_access() {
        var viewer = data.loginAsViewer(client);
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/support-requests/" + requestId + "/reply", Map.of()).bearerAuth(viewer), Map.class));
        assertEquals(403, e.getStatus().getCode());

        var analyst = data.loginAsAnalyst(client);
        var reply = client.toBlocking().retrieve(
                HttpRequest.POST("/api/support-requests/" + requestId + "/reply", Map.of()).bearerAuth(analyst), Map.class);
        assertEquals("Flat 4B, deliver after 5pm", reply.get("text"));
        long logged = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM pii_unmask_log WHERE field = 'support_reply'"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
        assertEquals(1L, logged);
    }

    @Test void resolving_records_the_note_and_audits_and_cannot_happen_twice() {
        var analyst = data.loginAsAnalyst(client);
        var done = client.toBlocking().retrieve(HttpRequest.POST("/api/support-requests/" + requestId + "/resolve",
                Map.of("note", "Rescheduled in Shiprocket for 16 Oct")).bearerAuth(analyst), Map.class);
        assertEquals("done", done.get("status"));
        assertEquals("Rescheduled in Shiprocket for 16 Oct", done.get("note"));
        assertEquals(1L, data.countAudit("support.resolve"));

        var open = client.toBlocking().retrieve(HttpRequest.GET("/api/support-requests").bearerAuth(analyst), Argument.listOf(Map.class));
        assertTrue(open.isEmpty());
        var again = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/support-requests/" + requestId + "/resolve", Map.of()).bearerAuth(analyst), Map.class));
        assertEquals(409, again.getStatus().getCode());
    }
}
