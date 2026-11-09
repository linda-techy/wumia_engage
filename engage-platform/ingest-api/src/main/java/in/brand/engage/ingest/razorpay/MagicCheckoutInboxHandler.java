package in.brand.engage.ingest.razorpay;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.shopify.CartTokens;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.shopify.ShopifyInboxHandler;
import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.IdentityResolver;
import in.brand.engage.persistence.IdentityResolver.Key;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Razorpay Magic Checkout's abandoned-cart webhook as a checkout signal source
 * (ADR D3; phase-5 §1 {@code CheckoutSignalSource}). Since the storefront's
 * checkout button opens Magic, Shopify sends no checkouts/* webhook for most
 * shoppers, so this is where checkout_abandon now learns that someone left.
 *
 * <p>The row lands in {@code checkouts} with {@code source = 'razorpay_magic'}
 * and the same {@code checkout_updated} event a Shopify checkout emits, so
 * {@code CheckoutAbandon} and {@code PaymentFailed} work unchanged.
 *
 * <p>Order-independent: a Magic order carries no checkout token, so if the
 * order is already stored (same cart, or same phone after the session) the
 * row is written completed and the journey cancels instead of sending. The
 * reverse order is handled by {@code ShopifyInboxHandler} closing open Magic
 * rows when the order arrives. Replays are deduped in the inbox, and an older
 * payload never overwrites a newer one.
 *
 * <p>Consent: the documented payload carries none. Nothing is granted here
 * unless the payload does carry Magic's consent field and its copy is
 * registered (engage.razorpay-magic.consent-copy-version). Without consent the
 * WhatsApp steps are blocked by policy as usual; push still runs.
 */
@Singleton
public class MagicCheckoutInboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MagicCheckoutInboxHandler.class);
    /** A Magic order this long after the session closes it, when nothing better links them. */
    static final int ORDER_MATCH_HOURS = 72;

    private final IdentityResolver identities;
    private final ConsentWriter consents;
    private final EventWriter events;
    private final boolean defaultRupees;
    private final String consentCopy;
    private final OffsetDateTime consentSince;
    private final int matchWindowMinutes;

    public MagicCheckoutInboxHandler(IdentityResolver identities, ConsentWriter consents, EventWriter events,
                                     EngageProperties.Magic magic, EngageProperties.Razorpay razorpay) {
        this.identities = identities;
        this.consents = consents;
        this.events = events;
        this.defaultRupees = "rupees".equalsIgnoreCase(blankToNull(magic.amountUnit()));
        this.consentCopy = blankToNull(magic.consentCopyVersion());
        this.consentSince = ShopifyInboxHandler.noticeSince(magic.consentSince());
        this.matchWindowMinutes = razorpay.matchWindowMinutes() > 0 ? razorpay.matchWindowMinutes() : 30;
    }

    @Override
    public String source() {
        return "razorpay_magic";
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("magic_abandoned_checkout.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var cartToken = CartTokens.normalise(rs.getString("cart_token"));
            var token = checkoutToken(rs.getString("token"), cartToken);
            if (token == null) {
                LOG.warn("magic abandoned checkout {} has neither token nor cart_token: skipped (keys: {})",
                        item.deliveryId(), rs.getString("payload_keys"));
                return;
            }
            var contact = Msisdn.normalise(rs.getString("contact_phone_raw"));
            var shipping = Msisdn.normalise(rs.getString("shipping_phone_raw"));
            String phone = contact.orElse(shipping.orElse(null));
            String phoneSource = contact.isPresent() ? "contact" : shipping.isPresent() ? "shipping" : null;
            var email = rs.getString("email");
            var at = Sql.timestamp(rs, "received_at");

            // Magic's contact screen asks for the buyer's own number: BUYER trust.
            var keys = new ArrayList<Key>();
            keys.add(phone == null ? null
                    : "contact".equals(phoneSource) ? Key.buyer("phone", phone) : Key.weak("phone", phone));
            keys.add(Key.buyer("email", email));
            keys.add(Key.session("checkout_token", token));
            keys.add(Key.session("cart_token", cartToken));
            UUID identityId = identities.resolve(c, keys);

            Long cartPaise = cartTotal(c, cartToken);
            Long totalPaise = toPaise(rs.getString("line_items_total"), defaultRupees, cartPaise);
            if (totalPaise != null && cartPaise == null && totalPaise < 5_000) {
                LOG.warn("magic checkout {}: total {} paise looks like rupees; check RAZORPAY_MAGIC_AMOUNT_UNIT",
                        token, totalPaise);
            }
            var recoveryUrl = recoveryUrl(rs.getString("permalink_items"), rs.getString("checkout_url"));

            // Closed at once if its order is already stored: same cart, or the same
            // phone ordering from shortly before the webhook to ORDER_MATCH_HOURS after.
            Sql.update(c, """
                    INSERT INTO checkouts (token, cart_token, identity_id, phone, phone_source, email, total_paise,
                                           recovery_url, source, completed_at, order_id, updated_at)
                    SELECT ?::text, ?::text, ?::uuid, ?::text, ?::text, ?::text, ?::bigint, ?::text, 'razorpay_magic',
                           o.created_at, o.id, ?::timestamptz
                      FROM (SELECT 1) one
                      LEFT JOIN LATERAL (
                        SELECT x.id, x.created_at FROM orders x
                         WHERE x.cancelled_at IS NULL
                           AND (x.cart_token = ?::text
                                OR (x.phone = ?::text AND x.created_at BETWEEN ?::timestamptz - interval '2 hours'
                                                                    AND ?::timestamptz + make_interval(hours => ?)))
                         ORDER BY (x.cart_token IS NOT DISTINCT FROM ?::text) DESC, x.created_at
                         LIMIT 1) o ON true
                    ON CONFLICT (token) DO UPDATE SET
                      cart_token   = COALESCE(EXCLUDED.cart_token, checkouts.cart_token),
                      identity_id  = COALESCE(EXCLUDED.identity_id, checkouts.identity_id),
                      phone        = COALESCE(EXCLUDED.phone, checkouts.phone),
                      phone_source = COALESCE(EXCLUDED.phone_source, checkouts.phone_source),
                      email        = COALESCE(EXCLUDED.email, checkouts.email),
                      total_paise  = COALESCE(EXCLUDED.total_paise, checkouts.total_paise),
                      recovery_url = COALESCE(EXCLUDED.recovery_url, checkouts.recovery_url),
                      completed_at = COALESCE(checkouts.completed_at, EXCLUDED.completed_at),
                      order_id     = COALESCE(checkouts.order_id, EXCLUDED.order_id),
                      updated_at   = EXCLUDED.updated_at
                    WHERE checkouts.updated_at <= EXCLUDED.updated_at""",
                    token, cartToken, identityId, phone, phoneSource, email, totalPaise, recoveryUrl, at,
                    cartToken, phone, at, at, ORDER_MATCH_HOURS, cartToken);

            if (cartToken != null) {
                // Keep a cart we already hold (Shopify's lines are in paise and fresher);
                // otherwise record Magic's lines so the reminder can name the product.
                Sql.update(c, """
                        INSERT INTO carts (cart_token, identity_id, checkout_token, item_count, total_paise, lines, updated_at)
                        SELECT ?::text, ?::uuid, ?::text, ?::int, ?::bigint,
                               COALESCE(jsonb_agg((l - 'price_raw') || jsonb_build_object('price_paise',
                                   CASE WHEN l->>'price_raw' !~ '^[0-9]+(\\.[0-9]+)?$' THEN NULL
                                        WHEN ?::boolean THEN round((l->>'price_raw')::numeric * 100)::bigint
                                        ELSE round((l->>'price_raw')::numeric)::bigint END)), '[]'::jsonb),
                               ?::timestamptz
                          FROM jsonb_array_elements(?::jsonb) l
                        ON CONFLICT (cart_token) DO UPDATE SET
                          identity_id    = COALESCE(carts.identity_id, EXCLUDED.identity_id),
                          checkout_token = EXCLUDED.checkout_token,
                          lines          = CASE WHEN carts.lines = '[]'::jsonb THEN EXCLUDED.lines ELSE carts.lines END""",
                        cartToken, identityId, token, rs.getInt("item_count"), totalPaise == null ? 0L : totalPaise,
                        usesRupees(rs.getString("line_items_total"), defaultRupees, cartPaise),
                        at, rs.getString("lines_json"));
            }

            // Only if Magic sends its consent field here and its copy is registered.
            if ("yes".equals(rs.getString("consent_raw")) && consentCopy != null && phone != null
                    && identityId != null && consentSince != null && at != null && !at.isBefore(consentSince)) {
                consents.grantWhatsAppFromMagicConsent(c, identityId, "checkout_token", token, phoneSource, at,
                        consentCopy);
            }

            // payment.failed can arrive before the session's abandonment: link it now.
            if (phone != null && totalPaise != null) {
                Sql.update(c, """
                        UPDATE payment_attempts
                           SET checkout_token = ?, match_method = 'phone_amount_window'
                         WHERE checkout_token IS NULL
                           AND phone = ?
                           AND abs(amount_paise - ?) <= 100
                           AND gateway_created_at BETWEEN ?::timestamptz - make_interval(mins => ?)
                                                      AND ?::timestamptz + interval '5 minutes'""",
                        token, phone, totalPaise, at, matchWindowMinutes, at);
            }

            LOG.info("magic abandoned checkout {} (phone {}, email {}, total {})", token,
                    phone != null, email != null, totalPaise);
            events.write(c, identityId, "checkout_updated", "razorpay_magic", "magic:" + item.deliveryId(),
                    Map.of("checkout_token", token, "has_phone", phone != null,
                           "phone_source", phoneSource == null ? "none" : phoneSource,
                           "checkout_source", "razorpay_magic"));
        }
    }

    /** Magic's own session token, else one derived from the Shopify cart token. */
    static String checkoutToken(String magicToken, String cartToken) {
        var t = blankToNull(magicToken);
        if (t != null) return t.length() > 200 ? t.substring(0, 200) : t;
        return cartToken == null ? null : "magic:" + cartToken;
    }

    /**
     * Where the reminder sends them: a Shopify cart permalink with
     * {@code storefront=true}, which refills the cart and lands on the cart page,
     * so the checkout button opens Magic again. Magic's own link is used only
     * when there are no variant ids; Storefront keeps just its path and query.
     */
    static String recoveryUrl(String permalinkItems, String magicUrl) {
        var items = blankToNull(permalinkItems);
        if (items != null) return "/cart/" + items + "?storefront=true";
        return blankToNull(magicUrl);
    }

    /**
     * Magic's line_items_total in paise. A decimal point means rupees. Otherwise
     * a cart we already hold (in paise, from Shopify) decides, and failing that
     * the configured unit.
     */
    static Long toPaise(String raw, boolean defaultRupees, Long knownCartPaise) {
        var v = parse(raw);
        if (v == null) return null;
        boolean rupees = usesRupees(raw, defaultRupees, knownCartPaise);
        return (rupees ? v.multiply(BigDecimal.valueOf(100)) : v).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static boolean usesRupees(String raw, boolean defaultRupees, Long knownCartPaise) {
        var v = parse(raw);
        if (v == null) return defaultRupees;
        if (raw.contains(".")) return true;
        if (knownCartPaise != null && knownCartPaise > 0) {
            long asPaise = v.longValue();
            long asRupees = v.multiply(BigDecimal.valueOf(100)).longValue();
            return Math.abs(asRupees - knownCartPaise) < Math.abs(asPaise - knownCartPaise);
        }
        return defaultRupees;
    }

    private static BigDecimal parse(String raw) {
        var s = blankToNull(raw);
        if (s == null) return null;
        try {
            var v = new BigDecimal(s.replace(",", ""));
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long cartTotal(Connection c, String cartToken) throws SQLException {
        if (cartToken == null) return null;
        try (var ps = Sql.prepare(c, "SELECT total_paise FROM carts WHERE cart_token = ?", cartToken);
             var rs = ps.executeQuery()) {
            return rs.next() ? Sql.nullableLong(rs, "total_paise") : null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
