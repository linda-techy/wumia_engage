package in.brand.engage.ingest.shopify;

import in.brand.engage.core.money.Paise;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code products/update} → {@code price_dropped} (P1-T04; the price_drop
 * push, P3-T07, consumes it).
 *
 * <p>Keeps the last price seen per variant in {@code variant_prices}. A newer
 * update with a lower price emits {@code price_dropped} with the old and new
 * price in paise; a rise, an unchanged price or an older (late) update emits
 * nothing. The first update seen for a variant sets the baseline: there is no
 * earlier price to have dropped from.
 */
@Singleton
public class PriceHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PriceHandler.class);

    private final EventWriter events;

    public PriceHandler(EventWriter events) {
        this.events = events;
    }

    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_product.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var productId = rs.getString("product_id");
            var title = rs.getString("product_title");
            var handle = rs.getString("product_handle");
            var updatedAt = Sql.timestamp(rs, "updated_at");
            var ids = (String[]) rs.getArray("variant_ids").getArray();
            var titles = (String[]) rs.getArray("variant_titles").getArray();
            var prices = (String[]) rs.getArray("prices").getArray();
            if (productId == null || updatedAt == null) return;

            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == null || prices[i] == null) continue;
                long price;
                try {
                    price = Paise.ofRupees(prices[i]).value();
                } catch (IllegalArgumentException | ArithmeticException e) {   // blank, negative, >2 decimals
                    LOG.warn("product {} variant {}: unreadable price '{}'; skipped", productId, ids[i], prices[i]);
                    continue;
                }
                variant(c, productId, title, handle, ids[i], titles[i], price, updatedAt);
            }
        }
    }

    private void variant(Connection c, String productId, String title, String handle, String variantId,
                         String variantTitle, long price, OffsetDateTime updatedAt) throws SQLException {
        Long previous = null;
        OffsetDateTime previousAt = null;
        try (var ps = Sql.prepare(c, "SELECT price_paise, updated_at FROM variant_prices WHERE variant_id = ? FOR UPDATE",
                variantId);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                previous = rs.getLong(1);
                previousAt = Sql.timestamp(rs, "updated_at");
            }
        }
        if (previous == null) {
            Sql.update(c, """
                    INSERT INTO variant_prices (variant_id, product_id, price_paise, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (variant_id) DO NOTHING""", variantId, productId, price, updatedAt);
            return;                                             // baseline: nothing to compare with
        }
        if (!updatedAt.isAfter(previousAt)) return;             // a late or replayed update changes nothing
        Sql.update(c, "UPDATE variant_prices SET price_paise = ?, product_id = ?, updated_at = ? WHERE variant_id = ?",
                price, productId, updatedAt, variantId);
        if (price >= previous) return;

        var props = new LinkedHashMap<String, Object>();
        props.put("variant_id", variantId);
        props.put("product_id", productId);
        props.put("product_title", title);
        props.put("product_handle", handle);
        props.put("variant_title", variantTitle);
        props.put("old_price_paise", previous);
        props.put("new_price_paise", price);
        events.write(c, null, "price_dropped", "shopify", "pricedrop:" + variantId + ":" + updatedAt.toInstant(), props);
    }
}
