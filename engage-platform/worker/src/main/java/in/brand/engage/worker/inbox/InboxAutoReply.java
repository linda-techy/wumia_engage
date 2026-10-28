package in.brand.engage.worker.inbox;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The greeting and away message the WhatsApp Business app used to send, now
 * from Engage (V50), as free-form service replies through the orchestrator
 * like every other message (CLAUDE.md §1.1). A new conversation gets an
 * instant answer with a menu:
 * <ul>
 * <li><b>Track my order</b>: the latest order's courier status and link;</li>
 * <li><b>Shop bestsellers</b>: the bestsellers page;</li>
 * <li><b>Talk to us</b>: a promise that a person replies (staff are alerted).</li>
 * </ul>
 * A message with an order number in it (WUM1001) gets that order's status
 * from Shopify, when the order's phone or email is the customer's own; anyone
 * else's order is left to staff, so nobody can read another person's order.
 * Inside support hours a new conversation gets the greeting; outside them the
 * away message, at most once per 12 h. Nothing is sent while staff are in the
 * conversation, to replies to Engage's own messages (their flows answer
 * those), or when the setting is off. Policy adds the WhatsApp halt and the
 * window; replies inside the window the customer opened need no opt-in and cost nothing.
 */
@Singleton
public class InboxAutoReply implements EventConsumer {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InboxAutoReply.class);

    public static final String GREETING = "inbox_greeting";
    public static final String AWAY = "inbox_away";
    public static final String AUTO = "inbox_auto_reply";

    static final String TRACK = "Track my order";
    static final String SHOP = "Shop bestsellers";

    static final Duration STAFF_ACTIVE = Duration.ofHours(2);
    static final Duration AUTO_GAP = Duration.ofHours(12);
    /** A message older than this when its event is handled (worker down) gets no greeting: too late to help. */
    static final Duration STALE = Duration.ofMinutes(10);

    private static final Set<String> EVENTS = Set.of("wa_message_received", "inbox_track", "inbox_shop", "inbox_talk",
            "wa_offer_requested");
    private static final Set<String> CONVERSATION_KINDS = Set.of("text", "image", "video", "audio", "document", "location");

    @Factory
    static class Definitions {
        @Bean
        @Singleton
        CascadeDefinition inboxGreeting() {
            return new CascadeDefinition(GREETING, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsappReply(GREETING)));
        }

        @Bean
        @Singleton
        CascadeDefinition inboxAway() {
            return new CascadeDefinition(AWAY, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsappReply(AWAY)));
        }

        @Bean
        @Singleton
        CascadeDefinition inboxAutoReply() {
            return new CascadeDefinition(AUTO, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsappReply(AUTO)));
        }
    }

    /** "WUM1001", "#wum1001", "wum-1001", "WUM 1001": the store's order numbers start with WUM. */
    static final Pattern ORDER_NUMBER = Pattern.compile("(?i)(?<![a-z0-9])#?\\s*wum[\\s-]*(\\d{3,8})(?!\\d)");

    private final InboxSettings settings;
    private final OrderLookup orders;
    private final Clock clock;

    public InboxAutoReply(InboxSettings settings, OrderLookup orders, Clock clock) {
        this.settings = settings;
        this.orders = orders;
        this.clock = clock;
    }

    @Override
    public boolean accepts(String eventName) {
        return EVENTS.contains(eventName);
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var id = e.identityId();
        var message = e.props().get("message_id");
        if (id == null || message == null || !settings.autoReply(c)) return List.of();
        var now = clock.instant();
        var hours = settings.hours(c);
        var name = firstName(c, id);

        if (!"wa_message_received".equals(e.name())) LOG.info("inbox: {} tapped by {}, answering", e.name(), id);
        switch (e.name()) {
            case "wa_offer_requested" -> {
                // The storefront's "Get my code on WhatsApp" (V54): the code they asked for, at once.
                return auto(id, message, welcome(name, settings.welcomeCode(c), settings.welcomeLabel(c)));
            }
            case "inbox_track" -> {
                return auto(id, message, track(c, id));
            }
            case "inbox_shop" -> {
                return auto(id, message, "Here are our bestsellers 💛 " + utm(settings.shopUrl(c)));
            }
            case "inbox_talk" -> {
                return auto(id, message, hours.open(now)
                        ? "Sure, " + name + "! Type your question here and a WUMIKA team member will reply shortly."
                        : "Sure, " + name + "! Type your question here and our team will reply after " + hours.opens() + ".");
            }
            default -> {
                // wa_message_received: an order number, a new conversation, or a message in one.
                var number = "text".equals(e.props().get("type")) ? orderNumber(body(c, message)) : null;
                if (number != null) {
                    if (staffActive(c, id, now)) return List.of();
                    return auto(id, message, orderReply(c, id, number));
                }
                if (!CONVERSATION_KINDS.contains(e.props().get("type")) || e.props().get("context_wamid") != null) return List.of();
                var at = receivedAt(c, message);
                var skip = at == null ? "not in the inbox" : at.isBefore(now.minus(STALE)) ? "too old"
                        : staffActive(c, id, now) ? "staff in the chat" : recentAuto(c, id, now) ? "auto-reply sent in the last 12 h" : null;
                if (skip != null) {
                    LOG.debug("inbox: no auto-reply to {} ({})", message, skip);
                    return List.of();
                }
                // One per person per 12 h block even if two messages race through before the first reply is logged.
                var block = now.getEpochSecond() / AUTO_GAP.toSeconds();
                if (hours.open(now)) {
                    if (ongoing(c, id, message, at)) return List.of();
                    LOG.info("inbox: greeting for {}", id);
                    return List.of(new MessageIntent(id, GREETING, "greet:" + id + ":" + block, Map.of("name", name), null));
                }
                LOG.info("inbox: away message for {} (support hours {} to {})", id, hours.opens(), hours.closes());
                return List.of(new MessageIntent(id, AWAY, "away:" + id + ":" + block,
                        Map.of("name", name, "opens", hours.opens(), "closes", hours.closes()), null));
            }
        }
    }

    private static List<MessageIntent> auto(UUID id, String message, String text) {
        return List.of(new MessageIntent(id, AUTO, "auto:" + message, Map.of("text", text), null));
    }

    /** The answer to "please send my code": the code and a link that applies it, or a plain welcome. */
    static String welcome(String name, String code, String label) {
        if (code == null || label == null) {
            return "Welcome to WUMIKA, " + name + "! ✨ You'll get your order updates, new drops and offers here. Reply STOP anytime.";
        }
        var redirect = java.net.URLEncoder.encode("/collections/all?sort_by=best-selling&utm_source=whatsapp&utm_medium=popup"
                + "&utm_campaign=wa_offer", java.nio.charset.StandardCharsets.UTF_8);
        return "Welcome to WUMIKA, " + name + "! ✨ Here's your " + label + " code: *" + code + "*\n\n"
                + "Tap to shop with it applied: https://www.wumika.com/discount/" + code + "?redirect=" + redirect + "\n\n"
                + "We'll also send your order updates and new drops here. Reply STOP anytime.";
    }

    static String utm(String url) {
        return url + (url.contains("?") ? "&" : "?") + "utm_source=whatsapp&utm_medium=inbox&utm_campaign=auto_menu";
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(InboxSettings.IST);

    /** The latest order, in the customer's words. */
    static String track(Connection c, UUID identity) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT o.order_number, o.cancelled_at IS NOT NULL, s.status, s.carrier, s.awb, s.tracking_url, s.status_at
                  FROM orders o
                  LEFT JOIN LATERAL (SELECT * FROM shipments WHERE order_id = o.id ORDER BY status_at DESC LIMIT 1) s ON true
                 WHERE o.identity_id = ? ORDER BY o.created_at DESC LIMIT 1""", identity); var rs = ps.executeQuery()) {
            if (!rs.next()) {
                return "Please reply with your order number. It starts with WUM, like WUM1001, and is in your order "
                        + "confirmation email.";
            }
            var order = rs.getString(1);
            if (rs.getBoolean(2)) return "Your order " + order + " was cancelled. If that's unexpected, reply here and our team will help.";
            var status = rs.getString(3);
            if (status == null) {
                return "Your order " + order + " is being packed. Orders are dispatched within 1–2 business days, and you'll get "
                        + "the tracking link as soon as it ships.";
            }
            var courier = rs.getString(4) == null ? "" : " with " + rs.getString(4) + (rs.getString(5) == null ? "" : " (AWB " + rs.getString(5) + ")");
            var link = rs.getString(6) == null ? "" : " Track it here: " + rs.getString(6);
            return switch (status) {
                case "delivered" -> "Your order " + order + " was delivered on " + DAY.format(rs.getTimestamp(7).toInstant())
                        + ". Something not right? Reply here with a photo within 7 days of delivery and we'll make it right.";
                case "out_for_delivery" -> "Your order " + order + " is out for delivery today" + courier + "." + link;
                case "ndr" -> "The courier couldn't deliver your order " + order + courier + ". Reply here with a good time or "
                        + "an updated address and we'll arrange redelivery." + link;
                case "rto" -> "Your order " + order + " is on its way back to us after delivery attempts failed. Reply here and "
                        + "our team will sort it out.";
                case "cancelled" -> "The shipment for your order " + order + " was cancelled. Reply here and our team will help.";
                default -> "Your order " + order + " is on its way" + courier + "." + link;
            };
        }
    }

    /** The order number in a message, as "WUM1001", or null. */
    static String orderNumber(String text) {
        if (text == null) return null;
        var m = ORDER_NUMBER.matcher(text);
        return m.find() ? "WUM" + m.group(1) : null;
    }

    /** "#WUM1001" and "WUM1001" are the same order. */
    static boolean sameOrder(String shopifyName, String number) {
        return shopifyName != null && shopifyName.replace("#", "").strip().equalsIgnoreCase(number);
    }

    /** The order's status for its owner; for anyone else, or when Shopify cannot say, a hand-over to staff. */
    String orderReply(Connection c, UUID id, String number) throws SQLException {
        OrderLookup.ShopOrder order;
        try {
            order = orders.byName(number).orElse(null);
        } catch (in.brand.engage.worker.shopify.ShopifyAdmin.Failure e) {
            LOG.warn("inbox: order {} could not be looked up in Shopify: {}", number, e.getMessage());
            return "Thanks! Our team will check order " + number + " and reply here shortly.";
        }
        if (order == null) {
            LOG.info("inbox: order {} asked by {} not found in Shopify", number, id);
            // The app reads the last 60 days of orders (no read_all_orders, owner's choice 2026-10-28).
            return "We couldn't find order " + number + " from the last 2 months. Please check the number in your order "
                    + "confirmation email and send it again. If it's an older order, our team will check it and reply here.";
        }
        if (!owns(c, id, order)) {
            LOG.info("inbox: order {} asked by {} belongs to another phone/email; left to staff", number, id);
            return "Thanks! For your privacy we only share order details with the phone number or email used on the order. "
                    + "Our team will check " + number + " and reply here shortly.";
        }
        LOG.info("inbox: order {} status sent to {}", number, id);
        return shopStatus(order);
    }

    /** The order's phone (any of them) or email is one of this customer's. */
    private static boolean owns(Connection c, UUID id, OrderLookup.ShopOrder order) throws SQLException {
        var mine = new java.util.HashSet<String>();
        try (var ps = Sql.prepare(c, "SELECT kind, value FROM identity_keys WHERE identity_id = ? AND kind IN ('phone', 'wa_id', 'email')", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                mine.add("email".equals(rs.getString(1)) ? "e:" + rs.getString(2).strip().toLowerCase(Locale.ROOT) : "p:" + last10(rs.getString(2)));
            }
        }
        if (order.email() != null && mine.contains("e:" + order.email().strip().toLowerCase(Locale.ROOT))) return true;
        for (var p : order.phones()) {
            var ten = last10(p);
            if (ten.length() == 10 && mine.contains("p:" + ten)) return true;
        }
        return false;
    }

    private static String last10(String phone) {
        var d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** Shopify's view of an order, in the customer's words. */
    static String shopStatus(OrderLookup.ShopOrder o) {
        var name = o.name().startsWith("#") ? o.name() : "#" + o.name();
        if (o.cancelled()) return "Your order " + name + " was cancelled. If that's unexpected, reply here and our team will help.";
        var f = o.fulfillments().isEmpty() ? null : o.fulfillments().getLast();
        if (f == null) {
            return "Your order " + name + " is being packed. Orders are dispatched within 1–2 business days, and you'll get "
                    + "the tracking link as soon as it ships.";
        }
        var courier = f.company() == null ? "" : " with " + f.company() + (f.number() == null ? "" : " (AWB " + f.number() + ")");
        var link = f.url() == null ? "" : " Track it here: " + f.url();
        return switch (f.status()) {
            case "DELIVERED" -> "Your order " + name + " was delivered" + (f.deliveredAt() == null ? "" : " on " + DAY.format(f.deliveredAt()))
                    + ". Something not right? Reply here with a photo within 7 days of delivery and we'll make it right.";
            case "OUT_FOR_DELIVERY" -> "Your order " + name + " is out for delivery today" + courier + "." + link;
            case "ATTEMPTED_DELIVERY", "FAILURE", "NOT_DELIVERED" -> "The courier couldn't deliver your order " + name + courier
                    + ". Reply here with a good time or an updated address and we'll arrange redelivery." + link;
            case "CANCELED" -> "The shipment for your order " + name + " was cancelled. Reply here and our team will help.";
            default -> "Your order " + name + " is on its way" + courier + "." + link;
        };
    }

    private static String body(Connection c, String wamid) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT body FROM wa_messages WHERE wamid = ?", wamid); var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /* ------------------------------- checks ------------------------------- */

    private static Instant receivedAt(Connection c, String wamid) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT at FROM wa_messages WHERE wamid = ?", wamid); var rs = ps.executeQuery()) {
            return rs.next() ? rs.getTimestamp(1).toInstant() : null;
        }
    }

    private static boolean staffActive(Connection c, UUID id, Instant now) throws SQLException {
        return exists(c, "SELECT 1 FROM wa_messages WHERE identity_id = ? AND direction = 'out' AND sent_by IS NOT NULL AND at > ?",
                id, utc(now.minus(STAFF_ACTIVE)));
    }

    private static boolean recentAuto(Connection c, UUID id, Instant now) throws SQLException {
        return exists(c, """
                SELECT 1 FROM wa_messages WHERE identity_id = ? AND direction = 'out'
                   AND template_key IN ('inbox_greeting', 'inbox_away') AND at > ?""", id, utc(now.minus(AUTO_GAP)));
    }

    /** The customer already wrote in the 12 h before: a conversation in progress, not a new one. */
    private static boolean ongoing(Connection c, UUID id, String wamid, Instant at) throws SQLException {
        return exists(c, "SELECT 1 FROM wa_messages WHERE identity_id = ? AND direction = 'in' AND wamid <> ? AND at < ? AND at > ?",
                id, wamid, utc(at), utc(at.minus(AUTO_GAP)));
    }

    static String firstName(Connection c, UUID id) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE(NULLIF(attrs->>'first_name', ''), NULLIF(split_part(attrs->>'wa_name', ' ', 1), ''))
                  FROM profiles WHERE identity_id = ?""", id); var rs = ps.executeQuery()) {
            var n = rs.next() ? rs.getString(1) : null;
            return n == null ? "there" : n;
        }
    }

    private static boolean exists(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static java.time.OffsetDateTime utc(Instant at) {
        return at.atOffset(java.time.ZoneOffset.UTC);
    }
}
