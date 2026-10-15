package in.brand.engage.worker.intents;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.CascadeDefinition.Step;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code order_tracking} (P4-T08, phase-4 §7): utility updates on WhatsApp,
 * with DLT SMS when WhatsApp cannot deliver.
 *
 * <table>
 * <caption>Event → cascade</caption>
 * <tr><td>order_placed</td><td>order_confirmed: WhatsApp only (Shopify emails the order)</td></tr>
 * <tr><td>order_shipped</td><td>order_shipped: WhatsApp, SMS 2 h later unless read</td></tr>
 * <tr><td>out_for_delivery</td><td>out_for_delivery: WhatsApp, SMS 30 min later unless read</td></tr>
 * <tr><td>delivery_failed</td><td>ndr_raised: WhatsApp, SMS 1 h later unless they reply</td></tr>
 * <tr><td>refund_initiated</td><td>refund_initiated: WhatsApp only (Shopify emails it)</td></tr>
 * <tr><td>order_delivered</td><td>nothing sent; ends the parcel's live updates</td></tr>
 * <tr><td>order_cancelled</td><td>ends every live update for the order</td></tr>
 * </table>
 *
 * <ul>
 * <li>Only for customers with a WhatsApp opt-in (phase-4 §7): everyone else
 *     keeps Shopify's own notifications. An opted-in number known to be
 *     {@code INCAPABLE} skips straight to SMS (guard), and 131026/131049
 *     escalate to SMS at once (P4-T07).</li>
 * <li>A later stage supersedes an earlier one: out for delivery ends the
 *     "shipped" run before its SMS, delivered ends them all.</li>
 * <li>Updates about a parcel that has already moved on, a cancelled order,
 *     or an event over a day old are not sent.</li>
 * </ul>
 */
@Singleton
public class OrderTracking implements EventConsumer {

    public static final String CONFIRMED = "order_confirmed";
    public static final String SHIPPED = "order_shipped";
    public static final String OUT_FOR_DELIVERY = "out_for_delivery";
    public static final String NDR = "ndr_raised";
    public static final String REFUND = "refund_initiated";
    static final Duration STALE = Duration.ofHours(24);
    static final String REFUND_DAYS = "5-7 working days";

    private final DefaultOrchestrator orchestrator;
    private final TrackRef refs;
    private final java.time.Clock clock;

    public OrderTracking(DefaultOrchestrator orchestrator, TrackRef refs, java.time.Clock clock) {
        this.orchestrator = orchestrator;
        this.refs = refs;
        this.clock = clock;
    }

    @Factory
    static class Definitions {

        @Bean
        @Singleton
        TrackRef trackRef(@Value("${SHOPIFY_API_SECRET:}") String appSecret) {
            return TrackRef.fromAppSecret(appSecret);
        }

        private static Step wa(String template) {
            return Step.whatsapp(template).require(Guard.WA_CAPABLE_OR_UNKNOWN);
        }

        @Bean @Singleton CascadeDefinition orderConfirmed() {
            return new CascadeDefinition(CONFIRMED, Priority.TRANSACTIONAL, List.of(wa("order_confirmed_v1")))
                    .succeedsOn("read");
        }

        @Bean @Singleton CascadeDefinition orderShipped() {
            return new CascadeDefinition(SHIPPED, Priority.TRANSACTIONAL, List.of(
                    wa("order_shipped_v1").waitAfter(Duration.ofHours(2)),
                    Step.sms("sms_order_shipped"))).succeedsOn("read", "delivered");
        }

        @Bean @Singleton CascadeDefinition outForDelivery() {
            return new CascadeDefinition(OUT_FOR_DELIVERY, Priority.TRANSACTIONAL, List.of(
                    wa("out_for_delivery_v1").waitAfter(Duration.ofMinutes(30)),
                    Step.sms("sms_out_for_delivery"))).succeedsOn("read", "delivered");
        }

        /** CLAUDE.md §3.3: a read is not a rescheduled delivery; only a reply or a button tap is. */
        @Bean @Singleton CascadeDefinition ndrRaised() {
            return new CascadeDefinition(NDR, Priority.TRANSACTIONAL, List.of(
                    wa("ndr_raised_v1").waitAfter(Duration.ofHours(1)),
                    Step.sms("sms_ndr"))).succeedsOn("replied");
        }

        @Bean @Singleton CascadeDefinition refundInitiated() {
            return new CascadeDefinition(REFUND, Priority.TRANSACTIONAL, List.of(wa("refund_initiated_v1")))
                    .succeedsOn("read");
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return switch (eventName) {
            case "order_placed", "order_shipped", "out_for_delivery", "delivery_failed", "order_delivered",
                 "order_cancelled", "refund_initiated" -> true;
            default -> false;
        };
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var orderId = e.props().get("order_id");
        if (orderId == null || orderId.isBlank()) return List.of();
        var shipmentId = e.props().get("shipment_id");
        var shipment = shipmentId == null ? null : "shipment:" + shipmentId;

        // Endings first: they apply whoever the customer is and however old the event.
        switch (e.name()) {
            case "order_cancelled" -> {
                for (var intent : List.of(CONFIRMED)) orchestrator.cancel(c, intent, "order:" + orderId, "order_cancelled");
                for (var s : shipments(c, orderId)) endParcel(c, s, "order_cancelled");
                return List.of();
            }
            case "order_delivered" -> {
                if (shipment != null) endParcel(c, shipment, "delivered");
                return List.of();
            }
            case "out_for_delivery" -> {
                if (shipment != null) orchestrator.cancel(c, SHIPPED, shipment, "superseded");
            }
            case "delivery_failed" -> {
                if (shipment != null) {
                    orchestrator.cancel(c, SHIPPED, shipment, "superseded");
                    orchestrator.cancel(c, OUT_FOR_DELIVERY, shipment, "superseded");
                }
            }
            default -> { }
        }

        if (e.identityId() == null || e.occurredAt().isBefore(clock.instant().minus(STALE))) return List.of();
        var order = order(c, orderId, e.identityId());
        if (order == null || order.cancelled() || !order.waOptedIn()) return List.of();

        var vars = new HashMap<String, String>();
        vars.put("order_no", order.number());
        vars.put("name", order.firstName() == null ? "there" : order.firstName());
        vars.put("amount", rupees(order.totalPaise()));
        return switch (e.name()) {
            case "order_placed" -> List.of(intent(e.identityId(), CONFIRMED, "order:" + orderId, vars));
            case "refund_initiated" -> {
                var refundId = e.props().get("refund_id");
                if (refundId == null) yield List.of();
                vars.put("amount", rupees(parseLong(e.props().get("amount_paise"))));
                vars.put("days", REFUND_DAYS);
                yield List.of(intent(e.identityId(), REFUND, "refund:" + refundId, vars));
            }
            default -> {
                if (shipmentId == null || parcelDone(c, Long.parseLong(shipmentId))) yield List.of();
                vars.put("courier", courier(e.props().get("carrier")));
                vars.put("awb", e.props().getOrDefault("awb", ""));
                vars.put("track_ref", refs.of(Long.parseLong(shipmentId)));
                yield switch (e.name()) {
                    case "order_shipped" -> List.of(intent(e.identityId(), SHIPPED, shipment, vars));
                    case "out_for_delivery" -> List.of(intent(e.identityId(), OUT_FOR_DELIVERY, shipment, vars));
                    case "delivery_failed" -> {
                        var reason = e.props().get("ndr_reason");
                        vars.put("reason", reason == null || reason.isBlank() ? "the courier could not complete it" : reason);
                        yield List.of(intent(e.identityId(), NDR, shipment + ":ndr", vars));
                    }
                    default -> List.of();
                };
            }
        };
    }

    private static MessageIntent intent(UUID identity, String key, String subject, Map<String, String> vars) {
        return new MessageIntent(identity, key, subject, Map.copyOf(vars), null);
    }

    private void endParcel(Connection c, String shipment, String reason) throws SQLException {
        orchestrator.cancel(c, SHIPPED, shipment, reason);
        orchestrator.cancel(c, OUT_FOR_DELIVERY, shipment, reason);
        orchestrator.cancel(c, NDR, shipment + ":ndr", reason);
    }

    record Order(String number, String firstName, long totalPaise, boolean cancelled, boolean waOptedIn) {}

    private static Order order(Connection c, String orderId, UUID identity) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT o.order_number, o.total_paise, o.cancelled_at IS NOT NULL AS cancelled,
                       p.attrs->>'first_name' AS first_name,
                       EXISTS (SELECT 1 FROM consent_current k WHERE k.identity_id = ? AND k.channel = 'whatsapp'
                                  AND k.state = 'granted') AS wa
                  FROM orders o LEFT JOIN profiles p ON p.identity_id = o.identity_id
                 WHERE o.id = ?""", identity, orderId);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new Order(rs.getString("order_number"), rs.getString("first_name"), rs.getLong("total_paise"),
                    rs.getBoolean("cancelled"), rs.getBoolean("wa"));
        }
    }

    private static List<String> shipments(Connection c, String orderId) throws SQLException {
        var out = new java.util.ArrayList<String>();
        try (var ps = Sql.prepare(c, "SELECT id FROM shipments WHERE order_id = ?", orderId); var rs = ps.executeQuery()) {
            while (rs.next()) out.add("shipment:" + rs.getLong(1));
        }
        return out;
    }

    /** Delivered, returned or cancelled: an update about an earlier stage would be wrong. */
    private static boolean parcelDone(Connection c, long shipmentId) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT status IN ('delivered','rto','cancelled') FROM shipments WHERE id = ?", shipmentId);
             var rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static String courier(String carrier) {
        return carrier == null || carrier.isBlank() ? "our courier" : carrier.strip();
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ₹12,499 or ₹1,23,456.50: Indian digit grouping, paise only when there are any. */
    static String rupees(long paise) {
        long r = paise / 100;
        long p = Math.abs(paise % 100);
        var digits = Long.toString(Math.abs(r));
        var out = new StringBuilder();
        if (digits.length() > 3) {
            var head = digits.substring(0, digits.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) out.append(',');
                out.append(head.charAt(i));
            }
            out.append(',').append(digits.substring(digits.length() - 3));
        } else {
            out.append(digits);
        }
        return (paise < 0 ? "-₹" : "₹") + out + (p == 0 ? "" : String.format(".%02d", p));
    }
}
