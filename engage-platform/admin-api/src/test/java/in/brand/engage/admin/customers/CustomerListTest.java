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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The customer count and masked list (owner, 2026-11-04), after the Shopify sync. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class CustomerListTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    String buyer;
    String lead;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        db.inTx(c -> Sql.update(c, "DELETE FROM webhook_inbox WHERE delivery_id LIKE 'sync:customer:%'"));
        buyer = shopifyCustomer("asha@example.com", "919876500001", "Asha", "7001", 3, true);
        lead = shopifyCustomer("ravi@example.com", "919876500002", "Ravi", "7002", 0, true);
        shopifyCustomer("meera@example.com", "919876500003", "Meera", "7003", 1, false);
        data.createIdentity("popup@example.com", "919876500004");          // not a Shopify customer
        db.inTx(c -> Sql.update(c, """
                INSERT INTO webhook_inbox (source, delivery_id, topic, payload, processed_at)
                VALUES ('shopify', 'sync:customer:7001:x', 'customers/update', '{}', now())"""));
    }

    String shopifyCustomer(String email, String phone, String name, String customerId, int orders, boolean wa) {
        var id = data.createIdentity(email, phone);
        db.inTx(c -> {
            var uuid = UUID.fromString(id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'shopify_customer', ?, true)",
                    uuid, customerId);
            Sql.update(c, """
                    UPDATE profiles SET attrs = attrs || jsonb_build_object('first_name', ?::text,
                           'shopify_orders_count', ?::int, 'shopify_spent_paise', 459900,
                           'shopify_last_order_at', CASE WHEN ?::int > 0 THEN now() - interval '90 days' END)
                     WHERE identity_id = ?""", name, orders, orders, uuid);
            if (wa) {
                Sql.update(c, """
                        INSERT INTO consents (identity_id, channel, purpose, state, source)
                        VALUES (?, 'whatsapp', 'marketing', 'granted', 'shopify_whatsapp')""", uuid);
            }
            return null;
        });
        return id;
    }

    Map<String, Object> get(String path, String token) {
        return client.toBlocking().retrieve(HttpRequest.GET(path).bearerAuth(token), Map.class);
    }

    @Test void the_summary_counts_shopify_customers_and_who_can_be_reached() {
        var s = get("/api/customers/summary", data.loginAsViewer(client));
        assertEquals(3, ((Number) s.get("customers")).intValue(), "the popup-only person is not a Shopify customer");
        assertEquals(3, ((Number) s.get("withPhone")).intValue());
        assertEquals(2, ((Number) s.get("whatsapp")).intValue());
        assertEquals(2, ((Number) s.get("buyers")).intValue());
        assertEquals(1, ((Number) s.get("reachableBuyers")).intValue());
        assertEquals(1, ((Number) ((Map<String, Object>) s.get("sync")).get("processed")).intValue());
    }

    @Test void the_list_is_masked_and_filtered() {
        var token = data.loginAsAnalyst(client);
        var all = get("/api/customers/list", token);
        assertEquals(3, ((Number) all.get("total")).intValue());
        var rows = (List<Map<String, Object>>) all.get("rows");
        assertFalse(rows.toString().contains("9876500001"), "no full phone in the list: " + rows);
        assertFalse(rows.toString().contains("@example.com"), "no email in the list");

        var reachable = (List<Map<String, Object>>) get("/api/customers/list?filter=reachable", token).get("rows");
        assertEquals(List.of(buyer), reachable.stream().map(r -> r.get("identityId")).toList());
        assertEquals("Asha", reachable.getFirst().get("firstName"));
        assertEquals(3, ((Number) reachable.getFirst().get("orders")).intValue());
        assertEquals("granted", reachable.getFirst().get("whatsapp"));

        var wa = (List<Map<String, Object>>) get("/api/customers/list?filter=whatsapp", token).get("rows");
        assertTrue(wa.stream().anyMatch(r -> lead.equals(r.get("identityId"))));
    }

    @Test void the_list_needs_an_analyst_and_a_known_filter() {
        var viewer = data.loginAsViewer(client);
        var e = assertThrows(HttpClientResponseException.class, () -> get("/api/customers/list", viewer));
        assertEquals(403, e.getStatus().getCode());
        var analyst = data.loginAsAnalyst(client);
        e = assertThrows(HttpClientResponseException.class, () -> get("/api/customers/list?filter=everyone", analyst));
        assertEquals(400, e.getStatus().getCode());
    }
}
