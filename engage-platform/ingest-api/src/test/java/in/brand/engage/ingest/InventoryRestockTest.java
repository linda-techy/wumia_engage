package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.ingest.inbox.InboxProcessor;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.shopify.ShopifyAdmin;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** inventory_levels/update → variant_restocked / variant_sold_out (P1-T03), through the inbox, against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class InventoryRestockTest {

    @Inject InboxRepository inbox;
    @Inject InboxProcessor processor;
    @Inject FakeShopifyAdmin admin;
    @Inject DataSource dataSource;

    String item;
    String variant;

    @BeforeEach
    void anItemOnTheDevStore() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
        }
        item = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
        variant = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
        admin.items.put(item, Optional.of(new ShopifyAdmin.VariantRef(variant, "777")));
        admin.failing = false;
        admin.calls.set(0);
    }

    @Test void zero_to_five_emits_one_restock_with_variant_product_and_quantity() throws SQLException {
        update("loc-1", 0, "2026-09-29T10:00:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");

        assertEquals(1, events("variant_restocked"));
        assertEquals("777|5", one("""
                SELECT props->>'product_id' || '|' || (props->>'quantity') FROM events
                 WHERE name = 'variant_restocked' AND props->>'variant_id' = ?""", variant));
    }

    @Test void five_to_seven_emits_nothing() throws SQLException {
        update("loc-1", 0, "2026-09-29T10:00:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");
        update("loc-1", 7, "2026-09-29T10:10:00Z");

        assertEquals(1, events("variant_restocked"), "only the 0 -> 5 crossing");
    }

    @Test void a_replayed_zero_to_five_emits_one() throws SQLException {
        update("loc-1", 0, "2026-09-29T10:00:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");    // same update, new delivery id

        assertEquals(1, events("variant_restocked"));
    }

    @Test void a_location_going_zero_to_three_while_another_holds_four_emits_nothing() throws SQLException {
        update("loc-A", 0, "2026-09-29T10:00:00Z");
        update("loc-B", 4, "2026-09-29T10:01:00Z");     // the variant came back: 0 -> 4 in total
        long before = events("variant_restocked");

        update("loc-A", 3, "2026-09-29T10:02:00Z");     // 4 -> 7 in total

        assertEquals(before, events("variant_restocked"));
        assertEquals(7, (Integer) one("SELECT available FROM inventory_state WHERE inventory_item_id = ?", item));
    }

    @Test void five_to_zero_emits_sold_out() throws SQLException {
        update("loc-1", 0, "2026-09-29T10:00:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");
        update("loc-1", 0, "2026-09-29T11:00:00Z");

        assertEquals(1, events("variant_sold_out"));
    }

    @Test void a_late_update_cannot_overwrite_a_newer_one() throws SQLException {
        update("loc-1", 0, "2026-09-29T10:00:00Z");
        update("loc-1", 5, "2026-09-29T10:05:00Z");
        update("loc-1", 0, "2026-09-29T10:03:00Z");     // older than the 5: arrived late

        assertEquals(5, (Integer) one("SELECT available FROM inventory_state WHERE inventory_item_id = ?", item));
        assertEquals(0, events("variant_sold_out"));
    }

    @Test void the_admin_api_is_asked_once_per_item_however_many_updates_arrive() throws SQLException {
        for (int i = 0; i < 5; i++) update("loc-1", i % 2 == 0 ? 0 : 4, "2026-09-29T10:0" + i + ":00Z");

        assertEquals(1, admin.calls.get());
    }

    @Test void first_sighting_in_stock_is_a_baseline_unless_shoppers_are_waiting() throws SQLException {
        update("loc-1", 6, "2026-09-29T10:00:00Z");
        assertEquals(0, events("variant_restocked"), "no previous total: nothing to compare with");

        var waited = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
        var waitedVariant = "w" + waited;
        admin.items.put(waited, Optional.of(new ShopifyAdmin.VariantRef(waitedVariant, "778")));
        var shopper = UUID.randomUUID();
        exec("INSERT INTO identities (id) VALUES (?)", shopper);
        exec("""
                INSERT INTO stock_waitlist (identity_id, variant_id, product_handle)
                VALUES (?, ?, 'linen-kurta')""", shopper, waitedVariant);
        var saved = item;
        item = waited;
        update("loc-1", 2, "2026-09-29T10:00:00Z");
        item = saved;

        assertEquals(1L, count("SELECT count(*) FROM events WHERE name = 'variant_restocked' AND props->>'variant_id' = ?", waitedVariant),
                "someone asked to be told: in stock now is a restock for them");
    }

    @Test void an_item_that_stocks_no_variant_is_acknowledged_and_ignored() throws SQLException {
        admin.items.put(item, Optional.empty());

        var id = update("loc-1", 5, "2026-09-29T10:00:00Z");

        assertNotNull(one("SELECT processed_at FROM webhook_inbox WHERE delivery_id = ?", id));
        assertEquals(0L, count("SELECT count(*) FROM inventory_levels WHERE inventory_item_id = ?", item));
    }

    @Test void an_admin_api_failure_leaves_the_update_in_the_inbox_for_a_retry() throws SQLException {
        admin.failing = true;

        var id = update("loc-1", 5, "2026-09-29T10:00:00Z");

        assertNull(one("SELECT processed_at FROM webhook_inbox WHERE delivery_id = ?", id));
        assertTrue(((String) one("SELECT last_error FROM webhook_inbox WHERE delivery_id = ?", id)).contains("THROTTLED"));
        assertEquals(0L, count("SELECT count(*) FROM inventory_levels WHERE inventory_item_id = ?", item));
    }

    /* -------------------------------- helpers -------------------------------- */

    /** Stores an inventory_levels/update as the webhook controller would, then drains the inbox. */
    String update(String location, int available, String updatedAt) {
        var deliveryId = "test-inv-" + UUID.randomUUID();
        var payload = """
                {"inventory_item_id": %s, "location_id": "%s", "available": %d, "updated_at": "%s",
                 "admin_graphql_api_id": "gid://shopify/InventoryLevel/%s?inventory_item_id=%s"}"""
                .formatted(item, location, available, updatedAt, location, item);
        assertEquals(InboxRepository.Stored.NEW,
                inbox.store("shopify", deliveryId, "inventory_levels/update", payload.getBytes(StandardCharsets.UTF_8)));
        processor.drain();
        return deliveryId;
    }

    long events(String name) throws SQLException {
        return count("SELECT count(*) FROM events WHERE name = ? AND props->>'variant_id' = ?", name, variant);
    }

    Object one(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    long count(String sql, Object... params) throws SQLException {
        return ((Number) one(sql, params)).longValue();
    }

    void exec(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }
}
