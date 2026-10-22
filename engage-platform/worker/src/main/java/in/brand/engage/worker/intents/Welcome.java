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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * {@code welcome} (owner's playbook, 2026-10-22, §3): right after someone opts
 * in and before they have bought, then day 2 and day 5: bestsellers, the
 * pieces customers love, and a first-order offer. Once per person, ended by
 * their first order (a click ends it too, as every promotional journey).
 *
 * <pre>
 * day 0   WhatsApp wa_welcome_v1 (free reply)  when they opted in on WhatsApp (START): inside the 24 h window
 *         Push     push_welcome_v1             when they allowed notifications
 * day 2   Push     push_welcome_bestsellers_v1 a live browser subscription
 * day 5   Push     push_welcome_offer_v1       journey.welcome.discount_code is set; auto-apply link
 * day 6   WhatsApp welcome_offer_v1            the same offer for those who did not tap, with WhatsApp marketing consent
 * </pre>
 */
@Singleton
public class Welcome implements EventConsumer {

    public static final String INTENT = "welcome";
    static final String PREFIX = "journey.welcome";
    static final Duration TTL = Duration.ofHours(24);
    static final String VIA_WA = "via_wa";
    static final String BESTSELLERS = "collections/best-sellers";

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final Storefront storefront;

    public Welcome(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition welcome() {
            var push = Guard.HAS_LIVE_DEVICE;
            return new CascadeDefinition(INTENT, Priority.LOW_INTENT, List.of(
                    Step.whatsappReply("wa_welcome_v1").require(Guard.hasVar(VIA_WA)).waitAfter(Duration.ofDays(2)),
                    Step.push("push_welcome_v1", TTL).require(Guard.lacksVar(VIA_WA), push).waitAfter(Duration.ofDays(2)),
                    Step.push("push_welcome_bestsellers_v1", TTL).require(push).waitAfter(Duration.ofDays(3)),
                    Step.push("push_welcome_offer_v1", TTL).require(push, Guard.hasVar("code"))
                            .withVarsFrom(WelcomeOffer.NAME).waitAfter(Duration.ofDays(1)),
                    Step.whatsapp("welcome_offer_v1").require(Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN, Guard.hasVar("code"))));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("push_subscribed") || eventName.equals("wa_start") || eventName.equals("order_placed");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var identity = e.identityId();
        if (identity == null) return List.of();
        if (e.name().equals("order_placed")) {
            orchestrator.cancel(c, INTENT, identity.toString(), "order_placed");
            return List.of();
        }
        try (var ps = Sql.prepare(c, """
                SELECT p.attrs->>'first_name' AS first_name,
                       EXISTS (SELECT 1 FROM orders o WHERE o.identity_id = i.id AND o.cancelled_at IS NULL) AS bought,
                       EXISTS (SELECT 1 FROM cascade_runs r WHERE r.identity_id = i.id AND r.intent_key = ?) AS welcomed
                  FROM identities i LEFT JOIN profiles p ON p.identity_id = i.id
                 WHERE i.id = ?""", INTENT, identity);
             var rs = ps.executeQuery()) {
            // Once per person, and only before the first order: a buyer is not new.
            if (!rs.next() || rs.getBoolean("bought") || rs.getBoolean("welcomed")) return List.of();

            var vars = new HashMap<String, String>();
            vars.put("name", CheckoutAbandon.firstName(rs.getString("first_name")));
            vars.put("url", storefront.path(BESTSELLERS));
            if (e.name().equals("wa_start")) vars.put(VIA_WA, "1");

            var snapshot = config.snapshot();
            var code = snapshot.text(PREFIX + ".discount_code", "*").strip();
            var label = snapshot.text(PREFIX + ".discount_label", "*").strip();
            if (!code.isEmpty() && !label.isEmpty()) {
                // Shopify's auto-apply link: the tap applies the code, nobody has to type it.
                var path = "discount/" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "?redirect=" + URLEncoder.encode("/" + BESTSELLERS, StandardCharsets.UTF_8);
                vars.put("code", code);
                vars.put("discount", label);
                vars.put("discount_path", path);
                vars.put("offer_url", storefront.path(path));
            }
            return List.of(new MessageIntent(identity, INTENT, identity.toString(), java.util.Map.copyOf(vars), null));
        }
    }
}
