package in.brand.engage.admin.customers;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Task 10: exact lookup, masked 360 view and audited reveal, against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class CustomerLookupTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    String identityId;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        identityId = data.createIdentity("mary@example.com", "918606572870");
    }

    Map<String, Object> get(String path, String token) {
        return client.toBlocking().retrieve(HttpRequest.GET(path).bearerAuth(token), Map.class);
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Test void finds_a_customer_by_exact_email_in_any_case() {
        var token = data.loginAsViewer(client);
        assertEquals(identityId, get("/api/customers?q=mary@example.com", token).get("identityId"));
        assertEquals(identityId, get("/api/customers?q=Mary@Example.com", token).get("identityId"));
    }

    @Test void finds_a_customer_by_phone_in_any_format() {
        var token = data.loginAsViewer(client);
        for (var q : List.of("918606572870", "8606572870", "+91 86065 72870")) {
            var body = get("/api/customers?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), token);
            assertEquals(identityId, body.get("identityId"), q);
        }
    }

    @Test void a_partial_email_or_phone_finds_nothing() {
        var token = data.loginAsViewer(client);
        for (var q : List.of("mary", "example.com", "@example.com", "860657", "")) {
            assertEquals(404, status(() -> get("/api/customers?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), token)),
                    "no wildcard search: '" + q + "' must not match");
        }
    }

    @Test void an_identity_merged_into_another_is_not_returned() {
        var survivor = data.createIdentity("survivor@example.com", "919999999999");
        db.inTx(c -> Sql.update(c, "UPDATE identities SET merged_into = ? WHERE id = ?",
                UUID.fromString(survivor), UUID.fromString(identityId)));
        var token = data.loginAsViewer(client);
        assertEquals(404, status(() -> get("/api/customers?q=mary@example.com", token)));
    }

    @Test void the_360_view_masks_contact_details() {
        var token = data.loginAsViewer(client);
        var body = get("/api/customers/" + identityId, token);
        var keys = (List<Map<String, Object>>) body.get("keys");
        var email = keys.stream().filter(k -> "email".equals(k.get("kind"))).findFirst().orElseThrow();
        assertEquals("m••••@example.com", email.get("value"));
        var phone = keys.stream().filter(k -> "phone".equals(k.get("kind"))).findFirst().orElseThrow();
        assertTrue(((String) phone.get("value")).contains("•"), phone.get("value") + " should be masked");
        assertFalse(body.toString().contains("mary@example.com"));
        assertFalse(body.toString().contains("8606572870"));
    }

    @Test void the_360_view_shows_orders_consent_and_devices_without_push_tokens() {
        var id = UUID.fromString(identityId);
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO orders (id, order_number, identity_id, total_paise, financial_status, created_at)
                    VALUES ('7001', '#1001', ?, 149900, 'paid', now())""", id);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source)
                    VALUES (?, 'whatsapp', 'transactional', 'granted', 'checkout_notice')""", id);
            return Sql.update(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, permission_source, consent_copy_ver)
                    VALUES (?, 'secret-fcm-token-value-123456', 'WEB', 'chrome', 'https://x', 'browse', 'push_v2')""", id);
        });
        var token = data.loginAsViewer(client);
        var body = get("/api/customers/" + identityId, token);

        var orders = (List<Map<String, Object>>) body.get("orders");
        assertEquals("#1001", orders.getFirst().get("orderNumber"));
        assertEquals(149900, ((Number) orders.getFirst().get("totalPaise")).intValue());
        var consent = (List<Map<String, Object>>) body.get("consent");
        assertEquals("checkout_notice", consent.getFirst().get("source"));
        assertEquals(Boolean.TRUE, consent.getFirst().get("inForce"));
        var devices = (List<Map<String, Object>>) body.get("devices");
        assertEquals("active", devices.getFirst().get("state"));
        assertFalse(body.toString().contains("secret-fcm-token-value"), "push tokens never reach the console");
    }

    @Test void an_unknown_or_malformed_id_is_not_found() {
        var token = data.loginAsViewer(client);
        assertEquals(404, status(() -> get("/api/customers/" + UUID.randomUUID(), token)));
        assertEquals(404, status(() -> get("/api/customers/not-a-uuid", token)));
    }

    @Test void reveal_is_one_field_with_a_reason_for_an_analyst_and_is_logged() {
        var viewerToken = data.loginAsViewer(client);
        var forbidden = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/api/customers/" + identityId + "/reveal", Map.of("field", "email", "reason", "Complaint #7"))
                        .bearerAuth(viewerToken)));
        assertEquals(403, forbidden.getStatus().getCode());
        assertEquals(0L, data.countAudit("customer.reveal"));

        var analystToken = data.loginAsAnalyst(client);
        assertEquals(400, status(() -> client.toBlocking().retrieve(HttpRequest.POST("/api/customers/" + identityId + "/reveal",
                Map.of("field", "email")).bearerAuth(analystToken), Map.class)), "a reason is required");
        assertEquals(400, status(() -> client.toBlocking().retrieve(HttpRequest.POST("/api/customers/" + identityId + "/reveal",
                Map.of("field", "address", "reason", "Complaint #7")).bearerAuth(analystToken), Map.class)));

        var body = client.toBlocking().retrieve(HttpRequest.POST("/api/customers/" + identityId + "/reveal",
                Map.of("field", "email", "reason", "Complaint #7")).bearerAuth(analystToken), Map.class);
        var keys = (List<Map<String, Object>>) body.get("keys");
        assertTrue(keys.stream().anyMatch(k -> "mary@example.com".equals(k.get("value"))));
        assertTrue(keys.stream().allMatch(k -> "email".equals(k.get("kind"))), "only the field asked for");
        assertEquals(1L, data.countAudit("customer.reveal"));
    }

    @Test void every_customer_endpoint_requires_sign_in() {
        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/customers?q=mary@example.com"), Map.class)));
        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/customers/" + identityId), Map.class)));
    }
}
