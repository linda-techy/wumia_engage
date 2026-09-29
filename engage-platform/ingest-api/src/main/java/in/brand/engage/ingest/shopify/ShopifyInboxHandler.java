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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies Shopify webhooks to the identity graph, checkouts, orders, carts and
 * the consent ledger. Every branch is an idempotent upsert, because Shopify
 * retries and because a checkout update can arrive after its order.
 */
@Singleton
public class ShopifyInboxHandler implements InboxHandler {

    private final IdentityResolver identities;
    private final ConsentWriter consents;
    private final EventWriter events;
    private final InventoryHandler inventory;
    private final int matchWindowMinutes;

    public ShopifyInboxHandler(IdentityResolver identities, ConsentWriter consents, EventWriter events,
                               InventoryHandler inventory, EngageProperties.Razorpay razorpay) {
        this.identities = identities;
        this.consents = consents;
        this.events = events;
        this.inventory = inventory;
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
            case "orders/create" -> order(c, item);
            case "carts/create", "carts/update" -> cart(c, item);
            case "customers/create", "customers/update" -> customer(c, item);
            case "inventory_levels/update" -> inventory.handle(c, item);
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

    private void order(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_order.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
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

            Sql.update(c, """
                    INSERT INTO orders (id, order_number, identity_id, cart_token, checkout_token, phone, phone_source,
                                        email, total_paise, financial_status, gateway_names, note_attributes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (id) DO NOTHING""",
                    orderId, rs.getString("order_number"), identityId, cartToken, checkoutToken, phone.value(),
                    phone.source(), email, total.value(), rs.getString("financial_status"),
                    textArray(rs, "gateway_names"), noteAttributes, createdAt);

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
            events.write(c, identityId, "order_placed", "shopify", "shopify:" + item.deliveryId(),
                    Map.of("order_id", orderId, "total_paise", total.value(),
                           "cart_token", cartToken == null ? "" : cartToken,
                           "checkout_token", checkoutToken == null ? "" : checkoutToken));
        }
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
