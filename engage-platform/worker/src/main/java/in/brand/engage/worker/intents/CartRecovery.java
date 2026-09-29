package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.DefaultOrchestrator;
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
import java.util.List;
import java.util.Map;

/**
 * {@code cart_recovery} step 1 (phase-3 §4): a push 45 minutes after the last
 * cart change. Every change restarts the cascade (cancel the live run, start a
 * new one), an emptied cart ends it, and {@code order_placed} for the cart
 * ends it. Push only in P3; email and WhatsApp steps arrive in P4/P5 behind
 * the same trigger.
 *
 * <p>Anonymous carts are skipped: without a linked identity there is nobody
 * to reach. The subject is the cart token, which already belongs to one person.
 */
@Singleton
public class CartRecovery implements EventConsumer {

    public static final String INTENT = "cart_recovery";
    static final Duration DELAY = Duration.ofMinutes(45);
    static final Duration TTL = Duration.ofHours(12);

    private final DefaultOrchestrator orchestrator;
    private final String cartUrl;

    public CartRecovery(DefaultOrchestrator orchestrator, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.cartUrl = storefront.cart();
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition cartRecovery() {
            return new CascadeDefinition(INTENT, Priority.MID_INTENT,
                    List.of(CascadeDefinition.Step.push("push_cart_recovery_v1", TTL)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("cart_updated") || eventName.equals("order_placed");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var cartToken = e.props().get("cart_token");
        if (cartToken == null || cartToken.isBlank()) return List.of();

        if (e.name().equals("order_placed")) {
            orchestrator.cancel(c, INTENT, cartToken, "order_placed");
            return List.of();
        }

        // The cart as it is now, not as the event saw it: a later change may already be stored.
        try (var ps = Sql.prepare(c, """
                SELECT identity_id, item_count, converted_at IS NOT NULL AS converted,
                       (SELECT l->>'title' FROM jsonb_array_elements(lines) l
                         ORDER BY (l->>'price_paise')::bigint DESC NULLS LAST LIMIT 1) AS product
                  FROM carts WHERE cart_token = ?""", cartToken);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return List.of();
            var identityId = rs.getObject("identity_id", java.util.UUID.class);
            var product = rs.getString("product");
            if (rs.getBoolean("converted")) {
                orchestrator.cancel(c, INTENT, cartToken, "order_placed");
                return List.of();
            }
            if (rs.getInt("item_count") == 0 || product == null) {
                orchestrator.cancel(c, INTENT, cartToken, "cart_emptied");
                return List.of();
            }
            if (identityId == null) return List.of();

            orchestrator.cancel(c, INTENT, cartToken, "cart_changed");
            // Lead with the most expensive item: the one most worth coming back for.
            return List.of(new MessageIntent(identityId, INTENT, cartToken,
                    Map.of("product", product, "url", cartUrl), e.occurredAt().plus(DELAY)));
        }
    }
}
