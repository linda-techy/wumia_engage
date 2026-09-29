package in.brand.engage.ingest.shopify;

import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code inventory_levels/update} → {@code variant_restocked} / {@code variant_sold_out} (P1-T03).
 *
 * <p>The webhook names an inventory item and one location, never the variant
 * and never the total. So: resolve item → variant with the Admin API once
 * ({@link #prepare}, outside the transaction; cached in {@code inventory_state}),
 * keep every location in {@code inventory_levels}, sum them, and compare the
 * new total with the last one. Only a crossing of zero is an event: 0 → 5
 * restocks, 5 → 7 is nothing, 5 → 0 sells out.
 *
 * <p>A late update never overwrites a newer one ({@code updated_at} guard),
 * a replay changes nothing, and two locations updating at once are serialised
 * per item by an advisory lock.
 *
 * <p>The first update seen for an item sets the baseline: its previous total
 * is unknown, so no event, with one exception. If shoppers are waiting on the
 * variant's waitlist and it is now in stock, that is a restock for them.
 */
@Singleton
public class InventoryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryHandler.class);

    private record Update(String itemId, String locationId, Integer available, OffsetDateTime updatedAt) {}

    private final Db db;
    private final ShopifyAdmin admin;
    private final EventWriter events;
    /** Lookups done in prepare(), waiting for handle(). Removed once stored in inventory_state. */
    private final Map<String, Optional<ShopifyAdmin.VariantRef>> resolved = new ConcurrentHashMap<>();

    public InventoryHandler(Db db, ShopifyAdmin admin, EventWriter events) {
        this.db = db;
        this.admin = admin;
        this.events = events;
    }

    /** Outside the transaction: the Admin API call, only for an item never mapped before. */
    public void prepare(InboxRepository.Item item) {
        var itemId = db.inTx(c -> {
            var u = update(c, item);
            if (u == null) return null;
            try (var ps = Sql.prepare(c, """
                    SELECT 1 FROM inventory_state
                     WHERE inventory_item_id = ? AND product_id IS NOT NULL""", u.itemId());
                 var rs = ps.executeQuery()) {
                return rs.next() ? null : u.itemId();
            }
        });
        if (itemId != null && !resolved.containsKey(itemId)) {
            resolved.put(itemId, admin.variantOfInventoryItem(itemId));
        }
    }

    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        var u = update(c, item);
        if (u == null || u.available() == null) return;          // untracked inventory: nothing to follow

        // One update at a time per item, across pods: the total must be read after the last write.
        try (var lock = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended('inventory:' || ?, 0))",
                u.itemId()); var ignored = lock.executeQuery()) {
            // held until commit
        }

        String variantId, productId;
        Integer previous;
        try (var ps = Sql.prepare(c, """
                SELECT variant_id, product_id, available FROM inventory_state WHERE inventory_item_id = ?""",
                u.itemId());
             var rs = ps.executeQuery()) {
            boolean seen = rs.next();
            if (seen && rs.getString("product_id") != null) {
                variantId = rs.getString("variant_id");
                productId = rs.getString("product_id");
                previous = rs.getInt("available");
            } else {
                var ref = resolved.get(u.itemId());
                if (ref == null) throw new IllegalStateException("inventory item " + u.itemId() + " not resolved yet");
                if (ref.isEmpty()) {
                    LOG.info("inventory item {} stocks no variant (deleted?); ignored", u.itemId());
                    return;
                }
                variantId = ref.get().variantId();
                productId = ref.get().productId();
                previous = seen ? rs.getInt("available") : null;   // a V3 row without product_id
            }
        }

        Sql.update(c, """
                INSERT INTO inventory_levels (inventory_item_id, location_id, available, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (inventory_item_id, location_id) DO UPDATE
                   SET available = EXCLUDED.available, updated_at = EXCLUDED.updated_at
                 WHERE inventory_levels.updated_at < EXCLUDED.updated_at""",
                u.itemId(), u.locationId(), u.available(), u.updatedAt());
        int total;
        try (var ps = Sql.prepare(c, "SELECT COALESCE(sum(available), 0) FROM inventory_levels WHERE inventory_item_id = ?",
                u.itemId());
             var rs = ps.executeQuery()) {
            rs.next();
            total = rs.getInt(1);
        }
        Sql.update(c, """
                INSERT INTO inventory_state (inventory_item_id, variant_id, product_id, available, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (inventory_item_id) DO UPDATE
                   SET variant_id = EXCLUDED.variant_id, product_id = EXCLUDED.product_id,
                       available = EXCLUDED.available,
                       updated_at = GREATEST(inventory_state.updated_at, EXCLUDED.updated_at)""",
                u.itemId(), variantId, productId, total, u.updatedAt());
        resolved.remove(u.itemId());

        boolean restocked = total > 0 && (previous != null ? previous <= 0 : waitlisted(c, variantId));
        boolean soldOut = total <= 0 && previous != null && previous > 0;
        if (restocked || soldOut) {
            var props = new LinkedHashMap<String, Object>();
            props.put("variant_id", variantId);
            props.put("product_id", productId);
            props.put("quantity", total);
            props.put("inventory_item_id", u.itemId());
            var name = restocked ? "variant_restocked" : "variant_sold_out";
            events.write(c, null, name, "shopify",
                    (restocked ? "restock:" : "soldout:") + variantId + ":" + u.updatedAt().toInstant(), props);
        }
    }

    private static boolean waitlisted(Connection c, String variantId) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT 1 FROM stock_waitlist WHERE variant_id = ? AND notified_at IS NULL LIMIT 1",
                variantId);
             var rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static Update update(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_inventory.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next() || rs.getString("inventory_item_id") == null) return null;
            Integer available = rs.getInt("available");
            if (rs.wasNull()) available = null;
            return new Update(rs.getString("inventory_item_id"), rs.getString("location_id"), available,
                    Sql.timestamp(rs, "updated_at"));
        }
    }
}
