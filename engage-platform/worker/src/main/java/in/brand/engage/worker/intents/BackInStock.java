package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code back_in_stock} (phase-3 §4): a restocked size variant alerts the
 * shoppers waiting for it.
 *
 * <p>At most quantity × 20 shoppers, oldest waitlist entry first: telling
 * 2,000 people about 6 units sells out in minutes and disappoints 1,994 of
 * them. The chosen rows are marked notified in the same transaction that
 * starts their intents, so a replay or a second worker cannot alert them
 * twice, and the next restock reaches the next people in line.
 *
 * <p>TTL one hour, urgency high, stale tokens allowed (the shopper asked for
 * this). The subject is {@code <variant>:<identity>}: one live run per
 * person, not one per variant across everyone.
 */
@Singleton
public class BackInStock implements EventConsumer {

    public static final String INTENT = "back_in_stock";
    static final int PER_UNIT = 20;
    static final String NO_SIZE_TEMPLATE = "push_back_in_stock_nosize_v1";

    private final Storefront storefront;

    public BackInStock(Storefront storefront) {
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition backInStock() {
            return new CascadeDefinition(INTENT, Priority.HIGH_INTENT, List.of(
                    CascadeDefinition.Step.push("push_back_in_stock_v1", Duration.ofHours(1))
                            .urgent().staleDevicesAllowed().orTemplate(NO_SIZE_TEMPLATE)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("variant_restocked");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var variantId = e.props().get("variant_id");
        int quantity = parseInt(e.props().get("quantity"));
        if (variantId == null || quantity <= 0) return List.of();
        var handle = e.props().get("product_handle");
        var productTitle = e.props().get("product_title");
        var variantTitle = e.props().get("variant_title");
        int limit = Math.min(quantity, 10_000) * PER_UNIT;

        var intents = new ArrayList<MessageIntent>();
        try (var ps = Sql.prepare(c, """
                UPDATE stock_waitlist w SET notified_at = now()
                  FROM (SELECT identity_id, variant_id FROM stock_waitlist
                         WHERE variant_id = ? AND notified_at IS NULL
                         ORDER BY created_at LIMIT ?
                           FOR UPDATE SKIP LOCKED) due
                 WHERE w.identity_id = due.identity_id AND w.variant_id = due.variant_id
                RETURNING w.identity_id, w.size_label, w.product_handle""", variantId, limit);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var identityId = rs.getObject("identity_id", UUID.class);
                var rowHandle = handle != null ? handle : rs.getString("product_handle");
                var vars = new HashMap<String, String>();
                vars.put("product", productTitle != null ? productTitle : fromHandle(rowHandle));
                vars.put("url", storefront.product(rowHandle, variantId));
                var size = size(rs.getString("size_label"), variantTitle);
                if (size != null) vars.put("size", size);
                else vars.put(CascadeDefinition.TEMPLATE_VAR, NO_SIZE_TEMPLATE);
                intents.add(new MessageIntent(identityId, INTENT, variantId + ":" + identityId, vars, null));
            }
        }
        return intents;
    }

    /** What the shopper saw on the notify-me button, else Shopify's variant title; none for a one-size item. */
    static String size(String sizeLabel, String variantTitle) {
        if (sizeLabel != null && !sizeLabel.isBlank()) return sizeLabel.strip();
        if (variantTitle == null || variantTitle.isBlank() || variantTitle.equalsIgnoreCase("Default Title")) return null;
        return variantTitle.strip();
    }

    /** "floral-anarkali-kurta" → "Floral Anarkali Kurta", when Shopify gave no title. */
    static String fromHandle(String handle) {
        var words = new ArrayList<String>();
        for (var w : handle.split("-")) {
            if (!w.isEmpty()) words.add(w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1));
        }
        return String.join(" ", words);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
