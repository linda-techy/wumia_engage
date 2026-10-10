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
import java.util.UUID;

/**
 * {@code checkout_abandon} (P5-T03), the way most Indian D2C brands run it
 * (owner's decision, 2026-10-10): WhatsApp first, push as the free fallback
 * and second nudge, a discount only at the end and only if an operator set
 * one. No SMS: promotional SMS is not built (CLAUDE.md §3.2).
 *
 * <pre>
 * t+30 min  WhatsApp checkout_reminder_v1  marketing opt-in, not INCAPABLE, cart ≥ wa_min_cart_paise
 *           (skipped → the push goes at once instead)
 * +4 h      Push     push_checkout_v1       a live browser subscription
 * +20 h     WhatsApp checkout_offer_v1      as step 1, and a discount applies: the operator's shared
 *                                          code, or (discount_mode = unique) a single-use code made
 *                                          at send time by AbandonDiscount
 * </pre>
 *
 * <ul>
 * <li>Every checkout update restarts the wait (the shopper is still in it);
 *     a completed checkout or {@code order_placed} ends the run.</li>
 * <li>Starting it ends {@code cart_recovery} for the same cart: one journey per basket.</li>
 * <li>The discount goes at most once per customer per
 *     {@code journey.abandon.discount_cooldown_days}, counting offers sent and
 *     offers still waiting in another live run, and optionally only to
 *     customers without a past order ({@code discount_new_customers_only}):
 *     otherwise shoppers learn to abandon for a code. Without it, the last
 *     step is skipped and the reminders still go.</li>
 * <li>WhatsApp goes to numbers not known to lack it: Meta bills a message only
 *     when delivered, so trying an UNKNOWN number costs nothing if it has no
 *     WhatsApp. Policy still applies consent, caps, quiet hours and budget.</li>
 * </ul>
 */
@Singleton
public class CheckoutAbandon implements EventConsumer {

    public static final String INTENT = "checkout_abandon";
    static final Duration TTL = Duration.ofHours(12);
    static final String WA_OK = "wa";

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final Storefront storefront;

    public CheckoutAbandon(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition checkoutAbandon() {
            var wa = new Guard[] {Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN, Guard.hasVar(WA_OK)};
            return new CascadeDefinition(INTENT, Priority.HIGH_INTENT, List.of(
                    Step.whatsapp("checkout_reminder_v1").require(wa).waitAfter(Duration.ofHours(4)),
                    Step.push("push_checkout_v1", TTL).require(Guard.HAS_LIVE_DEVICE).waitAfter(Duration.ofHours(20)),
                    Step.whatsapp("checkout_offer_v1").require(wa).require(Guard.hasVar("discount"))
                            .withVarsFrom(AbandonDiscount.NAME)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("checkout_updated") || eventName.equals("order_placed");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var token = e.props().get("checkout_token");
        if (token == null || token.isBlank()) return List.of();
        if (e.name().equals("order_placed")) {
            orchestrator.cancel(c, INTENT, token, "order_placed");
            return List.of();
        }

        // The checkout as it is now: a later update may already be stored.
        try (var ps = Sql.prepare(c, """
                SELECT k.identity_id, k.cart_token, k.total_paise, k.recovery_url,
                       k.completed_at IS NOT NULL OR k.order_id IS NOT NULL AS done,
                       p.attrs->>'first_name' AS first_name,
                       (SELECT l->>'title' FROM carts ct, jsonb_array_elements(ct.lines) l
                         WHERE ct.cart_token = k.cart_token
                         ORDER BY (l->>'price_paise')::bigint DESC NULLS LAST LIMIT 1) AS product
                  FROM checkouts k LEFT JOIN profiles p ON p.identity_id = k.identity_id
                 WHERE k.token = ?""", token);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return List.of();
            if (rs.getBoolean("done")) {
                orchestrator.cancel(c, INTENT, token, "checkout_completed");
                return List.of();
            }
            var identity = rs.getObject("identity_id", UUID.class);
            if (identity == null) return List.of();
            var cartToken = rs.getString("cart_token");
            if (cartToken != null) orchestrator.cancel(c, CartRecovery.INTENT, cartToken, "checkout_started");
            orchestrator.cancel(c, INTENT, token, "checkout_changed");

            var snapshot = config.snapshot();
            long total = rs.getLong("total_paise");
            var path = Storefront.checkoutPath(rs.getString("recovery_url"));
            Map<String, String> vars = new HashMap<>();
            vars.put("name", firstName(rs.getString("first_name")));
            vars.put("product", product(rs.getString("product")));
            vars.put("checkout_path", path);
            vars.put("url", storefront.path(path));
            vars.put("cart_value_paise", Long.toString(total));
            if (total >= snapshot.longValue("journey.checkout_abandon.wa_min_cart_paise", "*")) vars.put(WA_OK, "1");
            if ("unique".equals(snapshot.text("journey.abandon.discount_mode", "*").strip())) {
                // The code itself is made when the discount message goes (AbandonDiscount).
                if (discountAllowed(c, identity, token, e, snapshot)) {
                    vars.put("discount_mode", "unique");
                    vars.put("discount", snapshot.longValue("journey.abandon.discount_percent", "*") + "% off");
                }
            } else {
                var code = snapshot.text("journey.abandon.discount_code", "*").strip();
                var label = snapshot.text("journey.abandon.discount_label", "*").strip();
                if (!code.isEmpty() && !label.isEmpty() && discountAllowed(c, identity, token, e, snapshot)) {
                    vars.put("code", code);
                    vars.put("discount", label);
                }
            }
            var stall = Duration.ofMinutes(snapshot.longValue("journey.checkout_abandon.stall_minutes", "*"));
            return List.of(new MessageIntent(identity, INTENT, token, Map.copyOf(vars), e.occurredAt().plus(stall)));
        }
    }

    /** May this customer get the discount on this checkout? Decided when the run starts. */
    private static boolean discountAllowed(Connection c, UUID identity, String token, Event e,
                                           in.brand.engage.policy.ConfigSnapshot snapshot) throws SQLException {
        var since = e.occurredAt().minus(Duration.ofDays(snapshot.longValue("journey.abandon.discount_cooldown_days", "*")))
                .atOffset(java.time.ZoneOffset.UTC);
        boolean newOnly = snapshot.boolValue("journey.abandon.discount_new_customers_only", "*");
        try (var ps = Sql.prepare(c, """
                SELECT EXISTS (SELECT 1 FROM sends s
                                WHERE s.identity_id = ? AND s.template_key = 'checkout_offer_v1'
                                  AND s.status IN ('queued','sent','delivered','read','clicked') AND s.created_at > ?)
                    OR EXISTS (SELECT 1 FROM cascade_runs r
                                WHERE r.identity_id = ? AND r.intent_key = ? AND r.subject_key <> ?
                                  AND r.status IN ('active','waiting') AND r.vars->>'discount' IS NOT NULL)
                    OR (? AND EXISTS (SELECT 1 FROM orders o WHERE o.identity_id = ? AND o.cancelled_at IS NULL))""",
                identity, since, identity, INTENT, token, newOnly, identity);
             var rs = ps.executeQuery()) {
            rs.next();
            return !rs.getBoolean(1);
        }
    }

    static String firstName(String name) {
        return name == null || name.isBlank() ? "there" : name.strip();
    }

    static String product(String title) {
        return title == null || title.isBlank() ? "your picks" : title.strip();
    }
}
