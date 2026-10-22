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
 * The owner's playbook (2026-10-22, superseding WhatsApp-first of 2026-10-10):
 * push first because it is free, WhatsApp for those who did not act, then
 * WhatsApp with a coupon a day later. A tap on any of them ends the run.
 *
 * <pre>
 * t+30 min  Push     push_cart_recovery_v1   a live browser subscription
 *           (skipped → the WhatsApp goes at once instead)
 * +1 h      WhatsApp cart_reminder_v2        marketing opt-in, not INCAPABLE, cart ≥ wa_min_cart_paise
 * +24 h     WhatsApp cart_offer_v2           as above (WhatsApp marketing is one a day), and journey.abandon has a discount: the
 *                                            button opens the bag with the code applied
 * +24 h     Email    email_cart_v1           email marketing consent (skipped until SES is set up)
 * </pre>
 * SMS at 48 h (the playbook's last step) waits for a DLT-registered promotional template.
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
            var wa = new Guard[] {Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN, Guard.hasVar(CheckoutAbandon.WA_OK)};
            return new CascadeDefinition(INTENT, Priority.MID_INTENT, List.of(
                    Step.push("push_cart_recovery_v1", TTL).require(Guard.HAS_LIVE_DEVICE).waitAfter(Duration.ofHours(1)),
                    Step.whatsapp("cart_reminder_v2").require(wa).waitAfter(Duration.ofHours(24)),   // WhatsApp marketing: one a day
                    Step.whatsapp("cart_offer_v2").require(wa).require(Guard.hasVar("discount"))
                            .withVarsFrom(JourneyDiscount.NAME).waitAfter(Duration.ofHours(24)),
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
                       (SELECT l->>'product_id' FROM jsonb_array_elements(ct.lines) l
                         ORDER BY (l->>'price_paise')::bigint DESC NULLS LAST LIMIT 1) AS product_id,
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
            ProductImages.put(vars, ProductImages.byProductId(c, rs.getString("product_id")));
            vars.put("name", CheckoutAbandon.firstName(rs.getString("first_name")));
            vars.put("cart_value_paise", Long.toString(total));
            if (total >= snapshot.longValue("journey.cart_recovery.wa_min_cart_paise", "*")) vars.put(CheckoutAbandon.WA_OK, "1");
            // The day-later coupon uses the abandoned-checkout offer (journey.abandon.*): the same code rules.
            if (CheckoutAbandon.offerAllowed(c, identityId, e.occurredAt(), snapshot)) {
                if ("unique".equals(snapshot.text("journey.abandon.discount_mode", "*").strip())) {
                    vars.put("discount_mode", "unique");
                    vars.put("discount_config", "journey.abandon");
                    vars.put("discount", snapshot.longValue("journey.abandon.discount_percent", "*") + "% off");
                } else {
                    var code = snapshot.text("journey.abandon.discount_code", "*").strip();
                    var label = snapshot.text("journey.abandon.discount_label", "*").strip();
                    if (!code.isEmpty() && !label.isEmpty()) {
                        vars.put("code", code);
                        vars.put("discount", label);
                    }
                }
                vars.put("discount_redirect", "/cart");
            }
            var delay = Duration.ofMinutes(snapshot.longValue("journey.cart_recovery.start_delay_minutes", "*"));
            return List.of(new MessageIntent(identityId, INTENT, cartToken, Map.copyOf(vars), e.occurredAt().plus(delay)));
        }
    }
}
