package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.CascadeDefinition.Step;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.policy.ConfigSnapshot;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code winback}, "long time no see" (P5-T08), the way most Indian D2C
 * brands run it (owner's decision, 2026-10-11): a warm WhatsApp about what is
 * new, a push a week later, and an offer a week after that, only if a
 * discount is set. Started by {@code became_lapsed} ({@link LapsedCustomers}).
 *
 * <pre>
 * 11:00 IST  WhatsApp winback_v1        marketing opt-in, not INCAPABLE (skipped → the push goes at once)
 * +7 d       Push     push_winback_v1   a live browser subscription
 * +7 d       WhatsApp winback_offer_v1  as step 1, and a discount applies (shared code, or a
 *                                       single-use code made at send time by JourneyDiscount)
 * +3 d       Email    email_winback_v1   email marketing consent (skipped until SES is set up)
 * </pre>
 *
 * <ul>
 * <li>Any order ends the run. One live win-back per customer (subject = identity).</li>
 * <li>No discount for customers who returned half of what they bought or had two
 *     parcels come back undelivered (RTO): an offer would pay them to repeat it.
 *     They still get the reminders.</li>
 * <li>Low priority: every other journey comes first.</li>
 * </ul>
 */
@Singleton
public class Winback implements EventConsumer {

    public static final String INTENT = "winback";
    static final String PREFIX = "journey.winback";
    static final Duration TTL = Duration.ofHours(24);
    /** Late morning: when most Indian D2C brands send win-back. Policy quiet hours still apply. */
    static final LocalTime SEND_AT = LocalTime.of(11, 0);

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final Storefront storefront;

    public Winback(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition winback() {
            var wa = new Guard[] {Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN};
            return new CascadeDefinition(INTENT, Priority.LOW_INTENT, List.of(
                    Step.whatsapp("winback_v1").require(wa).waitAfter(Duration.ofDays(7)),
                    Step.push("push_winback_v1", TTL).require(Guard.HAS_LIVE_DEVICE).waitAfter(Duration.ofDays(7)),
                    Step.whatsapp("winback_offer_v1").require(wa).require(Guard.hasVar("discount"))
                            .withVarsFrom(JourneyDiscount.NAME).waitAfter(Duration.ofDays(3)),
                    Step.email("email_winback_v1").require(Guard.HAS_EMAIL_MARKETING)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("became_lapsed") || eventName.equals("order_placed");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var identity = e.identityId();
        if (identity == null) return List.of();
        if (e.name().equals("order_placed")) {
            orchestrator.cancel(c, INTENT, identity.toString(), "order_placed");
            return List.of();
        }

        var lastOrder = e.props().get("last_order_at");
        try (var ps = Sql.prepare(c, """
                SELECT p.attrs->>'first_name' AS first_name,
                       EXISTS (SELECT 1 FROM orders o WHERE o.identity_id = ? AND o.created_at > ?::timestamptz) AS ordered_since,
                       COALESCE((SELECT sum(o.refunded_paise)::numeric / NULLIF(sum(o.total_paise), 0)
                                   FROM orders o WHERE o.identity_id = ? AND o.cancelled_at IS NULL), 0) >= 0.5
                         OR (SELECT count(*) FROM shipments s JOIN orders o ON o.id = s.order_id
                              WHERE o.identity_id = ? AND s.status = 'rto') >= 2 AS returner
                  FROM identities i LEFT JOIN profiles p ON p.identity_id = i.id
                 WHERE i.id = ?""", identity, lastOrder, identity, identity, identity);
             var rs = ps.executeQuery()) {
            if (!rs.next() || rs.getBoolean("ordered_since")) return List.of();

            var snapshot = config.snapshot();
            var path = snapshot.text(PREFIX + ".link_path", "*").strip();
            Map<String, String> vars = new HashMap<>();
            vars.put("name", CheckoutAbandon.firstName(rs.getString("first_name")));
            vars.put("link_path", path);
            vars.put("url", storefront.path(path));
            if (!rs.getBoolean("returner")) discount(snapshot, vars);
            return List.of(new MessageIntent(identity, INTENT, identity.toString(), Map.copyOf(vars), sendTime(e.occurredAt())));
        }
    }

    /** The offer, if one is set: a single-use code made later, or the operator's shared code now. */
    static void discount(ConfigSnapshot snapshot, Map<String, String> vars) {
        if ("unique".equals(snapshot.text(PREFIX + ".discount_mode", "*").strip())) {
            vars.put("discount_mode", "unique");
            vars.put("discount_config", PREFIX);
            vars.put("discount", snapshot.longValue(PREFIX + ".discount_percent", "*") + "% off");
            return;
        }
        var code = snapshot.text(PREFIX + ".discount_code", "*").strip();
        var label = snapshot.text(PREFIX + ".discount_label", "*").strip();
        if (!code.isEmpty() && !label.isEmpty()) {
            vars.put("code", code);
            vars.put("discount", label);
        }
    }

    /** 11:00 IST on the day the lapse was found, or now if that is already past. */
    static Instant sendTime(Instant found) {
        var day = found.atZone(ConfigSnapshot.IST).toLocalDate();
        var at = day.atTime(SEND_AT).atZone(ConfigSnapshot.IST).toInstant();
        return at.isAfter(found) ? at : found;
    }
}
