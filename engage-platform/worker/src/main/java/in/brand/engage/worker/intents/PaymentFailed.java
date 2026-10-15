package in.brand.engage.worker.intents;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.CascadeDefinition.Step;
import in.brand.engage.orchestrator.CascadeRun;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.orchestrator.StepVars;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code payment_failed} (P5-T02, phase-5 §2.1): a gateway-confirmed failed
 * payment (Razorpay {@code payment.failed}). Utility, so it needs only a
 * WhatsApp opt-in, not marketing consent, and quiet hours do not hold it.
 *
 * <pre>
 * +5 min   WhatsApp payment_failed_v1     not INCAPABLE; skipped when the failure reached us 30+ min late
 * +25 min  Push     push_payment_retry_v1 a live browser subscription
 * +2 h     WhatsApp payment_failed_r2_v1  (payment_failed_r2b_v1 when the shopper cancelled)
 * +6 h     SMS      sms_payment_link      only if no WhatsApp of this run was read
 * </pre>
 *
 * <ul>
 * <li>Subject: the checkout token, or {@code pay:<payment id>} when the payment
 *     matched no checkout (its link falls back to the cart).</li>
 * <li>The first message is timed from the event, so a late webhook does not
 *     push it later still.</li>
 * <li>Ended by a captured payment, an order, or a completed checkout for the
 *     same checkout. It takes the checkout over from {@code checkout_abandon}:
 *     a failed payment is not an abandoned checkout, and the reminders must
 *     not claim otherwise or overlap.</li>
 * </ul>
 */
@Singleton
public class PaymentFailed implements EventConsumer {

    public static final String INTENT = "payment_failed";
    static final Duration LATE = Duration.ofMinutes(30);
    static final Duration TTL = Duration.ofHours(6);

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final Storefront storefront;
    private final Clock clock;

    public PaymentFailed(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront, Clock clock) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.storefront = storefront;
        this.clock = clock;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition paymentFailed() {
            return new CascadeDefinition(INTENT, Priority.TRANSACTIONAL, List.of(
                    Step.whatsapp("payment_failed_v1").require(Guard.WA_CAPABLE_OR_UNKNOWN, Guard.lacksVar("late"))
                            .waitAfter(Duration.ofMinutes(20)),
                    Step.push("push_payment_retry_v1", TTL).require(Guard.HAS_LIVE_DEVICE).waitAfter(Duration.ofMinutes(95)),
                    Step.whatsapp("payment_failed_r2_v1").orTemplate("payment_failed_r2b_v1")
                            .require(Guard.WA_CAPABLE_OR_UNKNOWN).waitAfter(Duration.ofHours(4)),
                    Step.sms("sms_payment_link").require(Guard.WA_UNREAD).withVarsFrom(PayRef.NAME)))
                    .succeedsOn("converted");
        }
    }

    /** The SMS pay link's signed run reference, made at send time (the run id does not exist earlier). */
    @Singleton
    public static class PayRef implements StepVars {
        public static final String NAME = "pay_ref";
        private final TrackRef refs;

        public PayRef(TrackRef refs) {
            this.refs = refs;
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public Map<String, String> provide(CascadeRun run, Map<String, String> vars) {
            return vars.containsKey("pay_ref") ? Map.of() : Map.of("pay_ref", refs.ofPayment(run.id()));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return switch (eventName) {
            case "payment_failed_confirmed", "payment_captured", "order_placed", "checkout_updated" -> true;
            default -> false;
        };
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var token = blank(e.props().get("checkout_token"));
        switch (e.name()) {
            case "payment_captured", "order_placed" -> {
                if (token != null) orchestrator.cancel(c, INTENT, token, e.name());
                return List.of();
            }
            case "checkout_updated" -> {
                if (token != null && completed(c, token)) orchestrator.cancel(c, INTENT, token, "checkout_completed");
                return List.of();
            }
            default -> { }
        }

        var identity = e.identityId();
        var paymentId = blank(e.props().get("payment_id"));
        if (identity == null || paymentId == null) return List.of();
        if (token != null && completed(c, token)) return List.of();     // paid on a retry before we got here
        if (token != null) orchestrator.cancel(c, CheckoutAbandon.INTENT, token, "payment_failed");

        String firstName = null;
        String recovery = null;
        try (var ps = Sql.prepare(c, """
                SELECT (SELECT p.attrs->>'first_name' FROM profiles p WHERE p.identity_id = ?) AS first_name,
                       (SELECT k.recovery_url FROM checkouts k WHERE k.token = ?) AS recovery_url""", identity, token);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                firstName = rs.getString("first_name");
                recovery = rs.getString("recovery_url");
            }
        }
        long amount = parseLong(e.props().get("amount_paise"));
        var path = Storefront.checkoutPath(recovery);
        Map<String, String> vars = new HashMap<>();
        vars.put("name", CheckoutAbandon.firstName(firstName));
        vars.put("amount", OrderTracking.rupees(amount));
        vars.put("amount_plain", OrderTracking.rupees(amount).replace("₹", ""));
        vars.put("payment_id", paymentId);
        vars.put("pay_path", path);
        vars.put("url", storefront.path(path));
        if ("CUSTOMER_CANCELLED".equals(e.props().get("failure_kind"))) vars.put("template", "payment_failed_r2b_v1");
        var delay = Duration.ofMinutes(config.snapshot().longValue("journey.payment_failed.first_delay_minutes", "*"));
        if (clock.instant().isAfter(e.occurredAt().plus(LATE))) vars.put("late", "1");
        var subject = token != null ? token : "pay:" + paymentId;
        return List.of(new MessageIntent(identity, INTENT, subject, Map.copyOf(vars), e.occurredAt().plus(delay)));
    }

    private static boolean completed(Connection c, String token) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT completed_at IS NOT NULL OR order_id IS NOT NULL FROM checkouts WHERE token = ?""", token);
             var rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
