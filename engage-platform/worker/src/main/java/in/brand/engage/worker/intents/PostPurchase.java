package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.CascadeDefinition.Step;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.policy.ConfigSnapshot;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code post_purchase} (P5-T06, owner's choice 2026-10-12): a "how do you
 * like it?" a few days after delivery, as Indian jewellery brands do, with
 * two quick replies.
 *
 * <ul>
 * <li>{@code journey.post_purchase.delay_days} (default 3) after delivery, at
 *     11:00 IST: WhatsApp {@code review_ask_v1} (marketing opt-in, not
 *     INCAPABLE) naming the order's main piece. Once per order; not for
 *     cancelled or refunded orders, and a cancellation or refund before then
 *     ends it.</li>
 * <li><b>Love it</b> ({@code review_love}): a free reply in the window the tap
 *     opened, {@code review_thanks}, asking for a photo or review on the
 *     product page.</li>
 * <li><b>Need help</b> ({@code review_help}): a free reply,
 *     {@code review_help}, and a {@code support_requested} event for the team.</li>
 * </ul>
 */
@Singleton
public class PostPurchase implements EventConsumer {

    public static final String INTENT = "post_purchase";
    public static final String THANKS = "review_thanks";
    public static final String HELP = "review_help";
    static final LocalTime SEND_AT = LocalTime.of(11, 0);
    static final Duration STALE = Duration.ofDays(2);
    private static final Logger LOG = LoggerFactory.getLogger(PostPurchase.class);

    private final DefaultOrchestrator orchestrator;
    private final ConfigResolver config;
    private final Storefront storefront;
    private final EventWriter events;
    private final Clock clock;

    public PostPurchase(DefaultOrchestrator orchestrator, ConfigResolver config, Storefront storefront,
                        EventWriter events, Clock clock) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.storefront = storefront;
        this.events = events;
        this.clock = clock;
    }

    @Factory
    static class Definitions {
        @Bean @Singleton CascadeDefinition postPurchase() {
            return new CascadeDefinition(INTENT, Priority.LOW_INTENT, List.of(
                    Step.whatsapp("review_ask_v1").require(Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN)))
                    .succeedsOn("replied");
        }

        @Bean @Singleton CascadeDefinition reviewThanks() {
            return new CascadeDefinition(THANKS, Priority.TRANSACTIONAL, List.of(Step.whatsappReply("review_thanks")));
        }

        @Bean @Singleton CascadeDefinition reviewHelp() {
            return new CascadeDefinition(HELP, Priority.TRANSACTIONAL, List.of(Step.whatsappReply("review_help")));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return switch (eventName) {
            case "order_delivered", "order_cancelled", "refund_initiated", "review_love", "review_help" -> true;
            default -> false;
        };
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        return switch (e.name()) {
            case "review_love", "review_help" -> answer(c, e);
            case "order_cancelled", "refund_initiated" -> {
                var orderId = e.props().get("order_id");
                if (orderId != null) orchestrator.cancel(c, INTENT, "review:" + orderId, e.name());
                yield List.of();
            }
            default -> delivered(c, e);
        };
    }

    private List<MessageIntent> delivered(Connection c, Event e) throws SQLException {
        var orderId = e.props().get("order_id");
        var identity = e.identityId();
        if (orderId == null || identity == null || e.occurredAt().isBefore(clock.instant().minus(STALE))) return List.of();
        var subject = "review:" + orderId;
        try (var ps = Sql.prepare(c, """
                SELECT o.cancelled_at IS NOT NULL OR o.refunded_paise > 0 AS skip,
                       EXISTS (SELECT 1 FROM cascade_runs r WHERE r.intent_key = ? AND r.subject_key = ?) AS asked,
                       (SELECT p.attrs->>'first_name' FROM profiles p WHERE p.identity_id = o.identity_id) AS first_name,
                       l.title, pr.handle
                  FROM orders o
                  LEFT JOIN LATERAL (SELECT title, product_id FROM order_lines ol WHERE ol.order_id = o.id
                                      ORDER BY ol.price_paise DESC NULLS LAST, ol.line_no LIMIT 1) l ON true
                  LEFT JOIN products pr ON pr.product_id = l.product_id
                 WHERE o.id = ?""", INTENT, subject, orderId);
             var rs = ps.executeQuery()) {
            // Once per order, however many parcels it came in.
            if (!rs.next() || rs.getBoolean("skip") || rs.getBoolean("asked")) return List.of();
            var handle = rs.getString("handle");
            Map<String, String> vars = new HashMap<>();
            vars.put("name", CheckoutAbandon.firstName(rs.getString("first_name")));
            vars.put("product", rs.getString("title") == null ? "order" : rs.getString("title"));
            vars.put("review_url", storefront.path(handle == null ? "collections/all"
                    : "products/" + URLEncoder.encode(handle, StandardCharsets.UTF_8)));
            vars.put("order_id", orderId);
            long days = config.snapshot().longValue("journey.post_purchase.delay_days", "*");
            return List.of(new MessageIntent(identity, INTENT, subject, Map.copyOf(vars), sendTime(e.occurredAt(), days)));
        }
    }

    /** A tap on Love it / Need help: the free reply, named from the run whose message was answered. */
    private List<MessageIntent> answer(Connection c, Event e) throws SQLException {
        var identity = e.identityId();
        var messageId = e.props().get("message_id");
        if (identity == null || messageId == null) return List.of();
        Map<String, String> vars = new HashMap<>();
        try (var ps = Sql.prepare(c, """
                SELECT r.vars->>'name', r.vars->>'review_url', r.vars->>'order_id'
                  FROM sends s JOIN cascade_runs r ON r.id = s.cascade_run_id
                 WHERE s.provider_id = ? AND s.channel = 'whatsapp' AND r.intent_key = ?""",
                e.props().getOrDefault("context_wamid", ""), INTENT);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                vars.put("name", rs.getString(1));
                vars.put("review_url", rs.getString(2));
                if (rs.getString(3) != null) vars.put("order_id", rs.getString(3));
            }
        }
        vars.putIfAbsent("name", "there");
        vars.putIfAbsent("review_url", storefront.path("collections/all"));
        if (e.name().equals("review_help")) {
            var props = new HashMap<String, Object>();
            props.put("reason", "post_purchase");
            props.put("message_id", messageId);
            if (vars.containsKey("order_id")) props.put("order_id", vars.get("order_id"));
            events.write(c, identity, "support_requested", "worker", "support:" + messageId, props);
            LOG.warn("customer {} asked for help after delivery (order {})", identity, vars.getOrDefault("order_id", "?"));
            return List.of(new MessageIntent(identity, HELP, "reply:" + messageId, Map.copyOf(vars), null));
        }
        return List.of(new MessageIntent(identity, THANKS, "reply:" + messageId, Map.copyOf(vars), null));
    }

    /** 11:00 IST, {@code days} after the delivery day. */
    static Instant sendTime(Instant delivered, long days) {
        return delivered.atZone(ConfigSnapshot.IST).toLocalDate().plusDays(days).atTime(SEND_AT)
                .atZone(ConfigSnapshot.IST).toInstant();
    }
}
