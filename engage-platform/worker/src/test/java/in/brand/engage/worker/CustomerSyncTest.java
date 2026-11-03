package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Admin API customer → the webhook shape ingest-api reads (shopify_customer.sql). */
class CustomerSyncTest {

    static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

    @Test
    void a_customer_becomes_a_customers_update_payload() {
        var p = CustomerSync.payload(Map.of(
                "legacyResourceId", "7001", "firstName", "Asha", "tags", List.of("VIP", "Kochi"),
                "updatedAt", "2026-11-03T18:00:00Z", "numberOfOrders", "3",
                "amountSpent", Map.of("amount", "4599.0"),
                "lastOrder", Map.of("createdAt", "2026-09-01T10:00:00Z"),
                "defaultAddress", Map.of("city", "Kochi", "provinceCode", "KL"),
                "defaultEmailAddress", Map.of("emailAddress", "asha@example.com", "marketingState", "SUBSCRIBED"),
                "defaultPhoneNumber", Map.of("phoneNumber", "+919876500001",
                        "smsMarketingConsent", Map.of("state", "NOT_SUBSCRIBED"),
                        "whatsAppMarketingConsent", Map.of("state", "SUBSCRIBED", "updatedAt", "2026-11-03T18:00:00Z",
                                "collectedFrom", "SHOPIFY"))), NOW);

        assertEquals("7001", p.get("id"));
        assertEquals("VIP, Kochi", p.get("tags"));
        assertEquals("+919876500001", p.get("phone"));
        assertEquals(Map.of("state", "subscribed", "consent_updated_at", "2026-11-03T18:00:00Z",
                "consent_collected_from", "SHOPIFY"), p.get("whatsapp_marketing_consent"));
        assertEquals("subscribed", ((Map<?, ?>) p.get("email_marketing_consent")).get("state"));
        assertEquals(3L, p.get("orders_count"));
        assertEquals("2026-09-01T10:00:00Z", p.get("last_order_at"));
        assertEquals("4599.0", p.get("total_spent"));
    }

    @Test
    void a_last_order_older_than_the_app_can_read_is_at_least_60_days_ago() {
        var p = CustomerSync.payload(Map.of("legacyResourceId", "7002", "numberOfOrders", 2), NOW);
        assertEquals("2026-09-05T06:00:00Z", p.get("last_order_at"));
        assertNull(p.get("phone"));
    }

    @Test
    void no_orders_no_date_and_no_id_no_payload() {
        assertNull(CustomerSync.payload(Map.of("legacyResourceId", "7003", "numberOfOrders", 0), NOW).get("last_order_at"));
        assertNull(CustomerSync.payload(Map.of("firstName", "x"), NOW));
    }

    @Test
    void a_campaign_message_carries_the_recipients_first_name() {
        assertEquals("Asha", CampaignExecutor.forRecipient(Map.of("code", "BAG10"), " Asha ").get("name"));
        assertEquals("there", CampaignExecutor.forRecipient(Map.of(), null).get("name"));
        assertEquals("BAG10", CampaignExecutor.forRecipient(Map.of("code", "BAG10"), "Asha").get("code"));
    }
}
