package in.brand.engage.ingest.shopify;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.money.Paise;
import in.brand.engage.core.shopify.CartTokens;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.IdentityResolver;
import in.brand.engage.persistence.IdentityResolver.Key;
import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies Shopify webhooks to the identity graph, checkouts, orders, carts and
 * the consent ledger. Every branch is an idempotent upsert, because Shopify
 * retries and because a checkout update can arrive after its order.
 */
@Singleton
public class ShopifyInboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ShopifyInboxHandler.class);

    private final IdentityResolver identities;
    private final ConsentWriter consents;
    private final EventWriter events;
    private final InventoryHandler inventory;
    private final PriceHandler prices;
    private final int matchWindowMinutes;

    public ShopifyInboxHandler(IdentityResolver identities, ConsentWriter consents, EventWriter events,
                               InventoryHandler inventory, PriceHandler prices, EngageProperties.Razorpay razorpay) {
        this.identities = identities;
        this.consents = consents;
        this.events = events;
        this.inventory = inventory;
        this.prices = prices;
        this.matchWindowMinutes = razorpay.matchWindowMinutes() > 0 ? razorpay.matchWindowMinutes() : 30;
    }

    @Override
    public String source() {
        return "shopify";
    }

    @Override
    public void prepare(InboxRepository.Item item) {
        if ("inventory_levels/update".equals(item.topic())) inventory.prepare(item);
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        switch (item.topic()) {
            case "checkouts/create", "checkouts/update" -> checkout(c, item);
            case "orders/create" -> order(c, item, true);
            case "orders/cancelled" -> orderCancelled(c, item);
            case "orders/paid" -> orderPaid(c, item);
            case "refunds/create" -> refund(c, item);
            case "carts/create", "carts/update" -> cart(c, item);
            case "customers/create", "customers/update" -> customer(c, item);
            case "inventory_levels/update" -> inventory.handle(c, item);
            case "products/update" -> prices.handle(c, item);
            case "app/uninstalled" -> uninstalled(c, item);
            default -> { /* acknowledged; other topics are consumed in later phases */ }
        }
    }

    /* ------------------------------ phones ------------------------------ */

    /** The buyer's contact phone wins; a shipping phone is used only when it is all we have. */
    record Phone(String value, String source) {
        static Phone pick(String contactRaw, String shippingRaw) {
            var contact = Msisdn.normalise(contactRaw);
            if (contact.isPresent()) return new Phone(contact.get(), "contact");
            return Msisdn.normalise(shippingRaw).map(s -> new Phone(s, "shipping")).orElse(new Phone(null, null));
        }

        /** Contact phone = BUYER trust; shipping phone may be a gift recipient's = WEAK. */
        Key key() {
            if (value == null) return null;
            return "contact".equals(source) ? Key.buyer("phone", value) : Key.weak("phone", value);
        }
    }

    /* ----------------------------- checkouts ---------------------------- */

    private void checkout(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_checkout.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var token = rs.getString("token");
            var cartToken = CartTokens.normalise(rs.getString("cart_token"));
            var customerId = rs.getString("customer_id");
            var phone = Phone.pick(rs.getString("contact_phone_raw"), rs.getString("shipping_phone_raw"));
            var email = rs.getString("email");
            var total = rs.getString("total_price");

            var keys = new ArrayList<Key>();
            if (customerId != null) keys.add(Key.verified("shopify_customer", customerId));   // HMAC-signed source
            keys.add(phone.key());
            keys.add(Key.buyer("email", email));
            keys.add(Key.session("checkout_token", token));
            keys.add(Key.session("cart_token", cartToken));
            UUID identityId = identities.resolve(c, keys);

            var totalPaise = total == null ? null : Paise.ofRupees(total).value();
            var updatedAt = Sql.timestamp(rs, "updated_at");

            // Order-independent: if orders/create was processed first, pick the
            // completion up from the orders table instead of recording an
            // abandoned checkout that was in fact paid. Older payloads never
            // overwrite newer ones; completion is sticky.
            Sql.update(c, """
                    INSERT INTO checkouts (token, cart_token, identity_id, phone, phone_source, email, total_paise,
                                           recovery_url, note_attributes, completed_at, order_id, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                            COALESCE(?, (SELECT o.created_at FROM orders o WHERE o.checkout_token = ? LIMIT 1)),
                            (SELECT o.id FROM orders o WHERE o.checkout_token = ? LIMIT 1),
                            ?)
                    ON CONFLICT (token) DO UPDATE SET
                      cart_token      = COALESCE(EXCLUDED.cart_token, checkouts.cart_token),
                      identity_id     = COALESCE(EXCLUDED.identity_id, checkouts.identity_id),
                      phone           = COALESCE(EXCLUDED.phone, checkouts.phone),
                      phone_source    = COALESCE(EXCLUDED.phone_source, checkouts.phone_source),
                      email           = COALESCE(EXCLUDED.email, checkouts.email),
                      total_paise     = COALESCE(EXCLUDED.total_paise, checkouts.total_paise),
                      recovery_url    = COALESCE(EXCLUDED.recovery_url, checkouts.recovery_url),
                      note_attributes = EXCLUDED.note_attributes,
                      completed_at    = COALESCE(checkouts.completed_at, EXCLUDED.completed_at),
                      order_id        = COALESCE(checkouts.order_id, EXCLUDED.order_id),
                      updated_at      = EXCLUDED.updated_at
                    WHERE checkouts.updated_at <= EXCLUDED.updated_at""",
                    token, cartToken, identityId, phone.value(), phone.source(), email, totalPaise,
                    rs.getString("recovery_url"), rs.getString("note_attributes_json"),
                    Sql.timestamp(rs, "completed_at"), token, token, updatedAt);

            if (cartToken != null) {
                Sql.update(c, """
                        UPDATE carts SET identity_id = COALESCE(identity_id, ?), checkout_token = ?
                         WHERE cart_token = ?""", identityId, token, cartToken);
            }

            // Razorpay can deliver payment.failed before Shopify delivers the
            // checkout. Link any such unmatched attempts now, with the same rule
            // razorpay_match_checkout.sql applies in the other direction.
            if (phone.value() != null && totalPaise != null) {
                Sql.update(c, """
                        UPDATE payment_attempts
                           SET checkout_token = ?, match_method = 'phone_amount_window'
                         WHERE checkout_token IS NULL
                           AND phone = ?
                           AND abs(amount_paise - ?) <= 100
                           AND ?::timestamptz BETWEEN gateway_created_at - make_interval(mins => ?)
                                                  AND gateway_created_at + interval '5 minutes'""",
                        token, phone.value(), totalPaise, updatedAt, matchWindowMinutes);
            }
            events.write(c, identityId, "checkout_updated", "shopify", "shopify:" + item.deliveryId(),
                    Map.of("checkout_token", token, "has_phone", phone.value() != null,
                           "phone_source", phone.source() == null ? "none" : phone.source()));
        }
    }

    /* ------------------------------ orders ------------------------------ */

    /** What the cancelled/paid handlers need from an order payload they have just upserted. */
    private record OrderRef(String orderId, UUID identityId, String cartToken, String checkoutToken,
                            OffsetDateTime cancelledAt, String cancelReason, String financialStatus) {}

    /**
     * Upserts the order from any order-shaped payload. orders/cancelled and
     * orders/paid can arrive before orders/create, so they upsert it too; only
     * orders/create ({@code placed}) emits {@code order_placed}.
     */
    private OrderRef order(Connection c, InboxRepository.Item item, boolean placed) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_order.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            var orderId = rs.getString("order_id");
            var cartToken = CartTokens.normalise(rs.getString("cart_token"));
            var checkoutToken = rs.getString("checkout_token");
            var customerId = rs.getString("customer_id");
            var phone = Phone.pick(rs.getString("contact_phone_raw"), rs.getString("shipping_phone_raw"));
            var email = rs.getString("email");
            var total = Paise.ofRupees(rs.getString("total_price"));
            var createdAt = Sql.timestamp(rs, "created_at");
            var noteAttributes = rs.getString("note_attributes_json");

            var keys = new ArrayList<Key>();
            if (customerId != null) keys.add(Key.verified("shopify_customer", customerId));
            keys.add(phone.key());
            keys.add(Key.buyer("email", email));
            keys.add(Key.session("checkout_token", checkoutToken));
            keys.add(Key.session("cart_token", cartToken));
            UUID identityId = identities.resolve(c, keys);

            // refunded_paise: refunds/create can beat the order; fold in any already recorded.
            Sql.update(c, """
                    INSERT INTO orders (id, order_number, identity_id, cart_token, checkout_token, phone, phone_source,
                                        email, total_paise, financial_status, gateway_names, note_attributes, created_at,
                                        refunded_paise)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?,
                            COALESCE((SELECT sum(amount_paise) FROM order_refunds WHERE order_id = ?), 0))
                    ON CONFLICT (id) DO NOTHING""",
                    orderId, rs.getString("order_number"), identityId, cartToken, checkoutToken, phone.value(),
                    phone.source(), email, total.value(), rs.getString("financial_status"),
                    textArray(rs, "gateway_names"), noteAttributes, createdAt, orderId);

            if (checkoutToken != null) {
                Sql.update(c, """
                        UPDATE checkouts SET completed_at = COALESCE(completed_at, ?), order_id = ?
                         WHERE token = ?""", createdAt, orderId, checkoutToken);
            }
            if (cartToken != null) {
                Sql.update(c, """
                        UPDATE carts SET converted_at = COALESCE(converted_at, ?), order_id = ?
                         WHERE cart_token = ?""", createdAt, orderId, cartToken);
            }
            if (identityId != null) {
                Sql.update(c, """
                        INSERT INTO conversions (identity_id, order_id, value_paise, occurred_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING""",
                        identityId, orderId, total.value(), createdAt);
                Sql.update(c, """
                        UPDATE profiles
                           SET attrs = attrs || jsonb_strip_nulls(jsonb_build_object(
                                 'first_name', ?::text, 'city', ?::text, 'state', ?::text)),
                               updated_at = now()
                         WHERE identity_id = ?""",
                        rs.getString("first_name"), rs.getString("city"), rs.getString("state_code"), identityId);

                // WhatsApp consent needs a phone to mean anything.
                if (phone.value() != null) {
                    consents.grantWhatsAppFromOrderAttributes(c, identityId, orderId, phone.source(), noteAttributes,
                            createdAt);
                    consents.applyPendingOptIns(c, identityId, orderId);
                }
                consents.syncShopifyEmailConsent(c, identityId, rs.getString("email_consent_state"),
                        "order:" + orderId, Sql.timestamp(rs, "email_consent_at"));
            }
            if (placed) {
                events.write(c, identityId, "order_placed", "shopify", "shopify:" + item.deliveryId(),
                        Map.of("order_id", orderId, "total_paise", total.value(),
                               "cart_token", cartToken == null ? "" : cartToken,
                               "checkout_token", checkoutToken == null ? "" : checkoutToken));
            }
            return new OrderRef(orderId, identityId, cartToken, checkoutToken, Sql.timestamp(rs, "cancelled_at"),
                    rs.getString("cancel_reason"), rs.getString("financial_status"));
        }
    }

    /** orders/cancelled: cancellation is sticky, and emits order_cancelled once per order. */
    private void orderCancelled(Connection c, InboxRepository.Item item) throws SQLException {
        var o = order(c, item, false);
        if (o == null || o.cancelledAt() == null) return;
        Sql.update(c, """
                UPDATE orders SET cancelled_at = COALESCE(cancelled_at, ?), cancel_reason = COALESCE(cancel_reason, ?),
                                  financial_status = COALESCE(?, financial_status)
                 WHERE id = ?""", o.cancelledAt(), o.cancelReason(), o.financialStatus(), o.orderId());
        // Keyed on the order, not the delivery: a re-sent cancellation is one event.
        events.write(c, o.identityId(), "order_cancelled", "shopify", "cancelled:" + o.orderId(),
                Map.of("order_id", o.orderId(), "reason", o.cancelReason() == null ? "" : o.cancelReason(),
                       "cart_token", o.cartToken() == null ? "" : o.cartToken(),
                       "checkout_token", o.checkoutToken() == null ? "" : o.checkoutToken()));
    }

    /** orders/paid: records the financial status; never un-does a cancellation. */
    private void orderPaid(Connection c, InboxRepository.Item item) throws SQLException {
        var o = order(c, item, false);
        if (o == null || o.financialStatus() == null) return;
        Sql.update(c, "UPDATE orders SET financial_status = ? WHERE id = ? AND cancelled_at IS NULL",
                o.financialStatus(), o.orderId());
    }

    /**
     * refunds/create: recorded once per refund id; orders.refunded_paise is the
     * sum for the order. A refund before its order is folded in when the order lands.
     */
    private void refund(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_refund.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var refundId = rs.getString("refund_id");
            var orderId = rs.getString("order_id");
            if (refundId == null || orderId == null) return;
            long amount = 0;
            for (var a : (String[]) rs.getArray("amounts").getArray()) amount += Paise.ofRupees(a).value();

            int inserted = Sql.update(c, """
                    INSERT INTO order_refunds (refund_id, order_id, amount_paise, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (refund_id) DO NOTHING""", refundId, orderId, amount, Sql.timestamp(rs, "created_at"));
            if (inserted == 0) return;                                  // a replay: counted already
            Sql.update(c, """
                    UPDATE orders SET refunded_paise = (SELECT COALESCE(sum(amount_paise), 0) FROM order_refunds
                                                         WHERE order_id = ?)
                     WHERE id = ?""", orderId, orderId);
            UUID identityId = null;
            try (var q = Sql.prepare(c, "SELECT identity_id FROM orders WHERE id = ?", orderId);
                 var r = q.executeQuery()) {
                if (r.next()) identityId = r.getObject(1, UUID.class);
            }
            events.write(c, identityId, "refund_initiated", "shopify", "refund:" + refundId,
                    Map.of("order_id", orderId, "refund_id", refundId, "amount_paise", amount));
        }
    }

    /**
     * app/uninstalled: nothing more will arrive from this shop. Logged at ERROR
     * (alert on it) and recorded as an event; there is no data to change.
     */
    private void uninstalled(Connection c, InboxRepository.Item item) throws SQLException {
        LOG.error("Shopify app uninstalled (delivery {}): no more webhooks will arrive until it is reinstalled",
                item.deliveryId());
        events.write(c, null, "app_uninstalled", "shopify", "shopify:" + item.deliveryId(), Map.of());
    }

    /* ------------------------------- carts ------------------------------ */

    private void cart(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_cart.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var cartToken = CartTokens.normalise(rs.getString("cart_token_raw"));
            if (cartToken == null) return;
            int itemCount = rs.getInt("item_count");
            long total = rs.getLong("total_paise");
            // Order-independent: a cart first seen after its order is already converted.
            Sql.update(c, """
                    INSERT INTO carts (cart_token, item_count, total_paise, lines, updated_at, converted_at, order_id)
                    VALUES (?, ?, ?, ?::jsonb, now(),
                            (SELECT o.created_at FROM orders o WHERE o.cart_token = ? LIMIT 1),
                            (SELECT o.id FROM orders o WHERE o.cart_token = ? LIMIT 1))
                    ON CONFLICT (cart_token) DO UPDATE SET
                      item_count = EXCLUDED.item_count, total_paise = EXCLUDED.total_paise,
                      lines = EXCLUDED.lines, updated_at = now(),
                      converted_at = COALESCE(carts.converted_at, EXCLUDED.converted_at),
                      order_id = COALESCE(carts.order_id, EXCLUDED.order_id)""",
                    cartToken, itemCount, total, rs.getString("lines_json"), cartToken, cartToken);

            UUID identityId = null;
            try (var q = Sql.prepare(c, "SELECT identity_id FROM carts WHERE cart_token = ?", cartToken);
                 var r = q.executeQuery()) {
                if (r.next()) identityId = r.getObject(1, UUID.class);
            }
            events.write(c, identityId, "cart_updated", "shopify", "shopify:" + item.deliveryId(),
                    Map.of("cart_token", cartToken, "item_count", itemCount, "total_paise", total));
        }
    }

    /* ----------------------------- customers ---------------------------- */

    private void customer(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_customer.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var customerId = rs.getString("customer_id");
            if (customerId == null) return;
            var phone = Msisdn.normalise(rs.getString("phone_raw")).orElse(null);

            UUID identityId = identities.resolve(c, List.of(
                    Key.verified("shopify_customer", customerId),
                    Key.buyer("phone", phone),                      // the phone on their own account
                    Key.buyer("email", rs.getString("email"))));

            Sql.update(c, """
                    UPDATE profiles
                       SET attrs = attrs || jsonb_strip_nulls(jsonb_build_object('first_name', ?::text)),
                           updated_at = now()
                     WHERE identity_id = ?""", rs.getString("first_name"), identityId);
            consents.syncShopifyEmailConsent(c, identityId, rs.getString("email_consent_state"),
                    "customer:" + customerId, Sql.timestamp(rs, "email_consent_at"));
            events.write(c, identityId, "customer_updated", "shopify", "shopify:" + item.deliveryId(),
                    Map.of("customer_id", customerId));
        }
    }

    private static String[] textArray(ResultSet rs, String column) throws SQLException {
        var arr = rs.getArray(column);
        return arr == null ? new String[0] : (String[]) arr.getArray();
    }
}
