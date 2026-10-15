package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.CascadeDefinition.Step;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cart_recovery} (P5-T04): items left in the cart, no checkout started.
 * WhatsApp first, as most Indian D2C brands do (owner's decision,
 * 2026-10-10), push as the free fallback and second nudge. No SMS.
 *
 * <pre>
 * t+45 min  WhatsApp cart_reminder_v1        marketing opt-in, not INCAPABLE, cart ≥ wa_min_cart_paise
 *           (skipped → the push goes at once instead)
 * +4 h      Push     push_cart_recovery_v1   a live browser subscription
 * +20 h     Email    email_cart_v1           email marketing consent (skipped until SES is set up)
 * </pre>
 *
 * Every cart change restarts the run (cancel the live one, start a new one),
 * an emptied cart ends it, and {@code order_placed} for the cart ends it. A
 * cart whose checkout was started recently belongs to {@code checkout_abandon},
 * which has the buyer's phone and Shopify's saved checkout.
 *
 * <p>Anonymous carts are skipped: without a linked identity there is nobody
 * to reach. The subject is the cart token, which already belongs to one person.
 */
@Singleton
public class CartRecovery implements EventConsumer {

    public static final String INTENT = "cart_recovery";
    static final Duration TTL = Duration.ofHours(12);

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final String cartUrl;

    public CartRecovery(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.cartUrl = storefront.cart();
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition cartRecovery() {
            return new CascadeDefinition(INTENT, Priority.MID_INTENT, List.of(
                    Step.whatsapp("cart_reminder_v1")
                            .require(Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN, Guard.hasVar(CheckoutAbandon.WA_OK))
                            .waitAfter(Duration.ofHours(4)),
                    Step.push("push_cart_recovery_v1", TTL).waitAfter(Duration.ofHours(20)),
                    Step.email("email_cart_v1").require(Guard.HAS_EMAIL_MARKETING)));
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
                SELECT ct.identity_id, ct.item_count, ct.total_paise, ct.converted_at IS NOT NULL AS converted,
                       p.attrs->>'first_name' AS first_name,
                       (SELECT l->>'title' FROM jsonb_array_elements(ct.lines) l
                         ORDER BY (l->>'price_paise')::bigint DESC NULLS LAST LIMIT 1) AS product,
                       EXISTS (SELECT 1 FROM checkouts k
                                WHERE k.cart_token = ct.cart_token AND k.identity_id IS NOT NULL
                                  AND k.completed_at IS NULL AND k.order_id IS NULL
                                  AND k.updated_at > ? - interval '24 hours') AS in_checkout
                  FROM carts ct LEFT JOIN profiles p ON p.identity_id = ct.identity_id
                 WHERE ct.cart_token = ?""", e.occurredAt().atOffset(java.time.ZoneOffset.UTC), cartToken);
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
            if (rs.getBoolean("in_checkout")) return List.of();       // checkout_abandon has it

            var snapshot = config.snapshot();
            long total = rs.getLong("total_paise");
            Map<String, String> vars = new HashMap<>();
            // Lead with the most expensive item: the one most worth coming back for.
            vars.put("product", product);
            vars.put("url", cartUrl);
            vars.put("name", CheckoutAbandon.firstName(rs.getString("first_name")));
            vars.put("cart_value_paise", Long.toString(total));
            if (total >= snapshot.longValue("journey.cart_recovery.wa_min_cart_paise", "*")) vars.put(CheckoutAbandon.WA_OK, "1");
            var delay = Duration.ofMinutes(snapshot.longValue("journey.cart_recovery.start_delay_minutes", "*"));
            return List.of(new MessageIntent(identityId, INTENT, cartToken, Map.copyOf(vars), e.occurredAt().plus(delay)));
        }
    }
}
