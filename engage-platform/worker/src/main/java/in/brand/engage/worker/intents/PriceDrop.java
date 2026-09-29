package in.brand.engage.worker.intents;

import in.brand.engage.core.money.Paise;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code price_drop} (phase-3 §4): a variant's price fell, and it sits in
 * someone's open cart or on their waitlist.
 *
 * <p>Real numbers in the copy ("Now ₹1,499, was ₹1,899"), no "sale" framing.
 * TTL 24 hours. Each person is reached once per drop even when the variant is
 * in their cart and on their waitlist; anonymous carts reach nobody. The
 * waitlist is only read: {@code notified_at} belongs to back-in-stock, and a
 * price drop is not the restock the shopper is waiting for.
 *
 * <p>The subject is {@code <variant>:<identity>}; the template's one-day
 * cooldown keeps a store-wide markdown from sending one push per item.
 */
@Singleton
public class PriceDrop implements EventConsumer {

    public static final String INTENT = "price_drop";
    static final Duration TTL = Duration.ofHours(24);

    private final Storefront storefront;

    public PriceDrop(Storefront storefront) {
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition priceDrop() {
            return new CascadeDefinition(INTENT, Priority.MID_INTENT,
                    List.of(CascadeDefinition.Step.push("push_price_drop_v1", TTL)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("price_dropped");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var variantId = e.props().get("variant_id");
        var handle = e.props().get("product_handle");
        long oldPrice = parseLong(e.props().get("old_price_paise"));
        long newPrice = parseLong(e.props().get("new_price_paise"));
        if (variantId == null || handle == null || newPrice <= 0 || newPrice >= oldPrice) return List.of();

        var title = e.props().get("product_title");
        var vars = Map.of(
                "product", title != null && !title.isBlank() ? title : BackInStock.fromHandle(handle),
                "new_price", rupees(newPrice),
                "old_price", rupees(oldPrice),
                "url", storefront.product(handle, variantId));

        var intents = new ArrayList<MessageIntent>();
        try (var ps = Sql.prepare(c, """
                SELECT identity_id FROM carts
                 WHERE converted_at IS NULL AND identity_id IS NOT NULL
                   AND lines @> jsonb_build_array(jsonb_build_object('variant_id', CAST(? AS text)))
                UNION
                SELECT identity_id FROM stock_waitlist WHERE variant_id = ? AND notified_at IS NULL""",
                variantId, variantId);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var identityId = rs.getObject("identity_id", UUID.class);
                intents.add(new MessageIntent(identityId, INTENT, variantId + ":" + identityId, vars, null));
            }
        }
        return intents;
    }

    /** 149900 → "₹1,499"; paise shown only when there are any. */
    static String rupees(long paise) {
        return "₹" + Paise.of(paise).toRupeeString();
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
