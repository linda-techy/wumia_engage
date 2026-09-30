package in.brand.engage.persistence;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends to the consent ledger. Never updates, never infers.
 *
 * <p>A grant covers exactly the purposes declared by the registered copy
 * version the shopper saw (V4 consent_copy_versions). An unknown copy version
 * grants NOTHING and logs a warning: consent without recorded wording is not
 * evidence of anything.
 *
 * <p><b>Every row is stamped with when the shopper made the choice, never with
 * processing time.</b> consent_current resolves by occurred_at, so a webhook
 * that arrives late, or is retried hours later from the inbox, lands in its
 * true place in history and cannot override a newer choice. Stamping with
 * now() would let a delayed order webhook re-subscribe someone who had since
 * unsubscribed, or re-grant WhatsApp after a STOP. Each statement also carries
 * a unique ref, so replaying the same webhook writes nothing.
 */
@Singleton
public class ConsentWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentWriter.class);

    /**
     * WhatsApp opt-in carried from the cart drawer into the order's
     * note_attributes. Grants one row per declared purpose, once per order.
     *
     * @return rows granted (0 if not opted in, copy unknown, or already applied)
     */
    public int grantWhatsAppFromOrderAttributes(Connection c, UUID identityId, String orderId,
                                                String phoneSource, String noteAttributesJson,
                                                OffsetDateTime orderCreatedAt) throws SQLException {
        // occurred_at = when the box was ticked (_engage_wa_at), else when the
        // order was placed. A malformed tick time falls back rather than failing.
        int n = Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, copy_version, evidence, occurred_at)
                SELECT ?, 'whatsapp', pur, 'granted', 'cart_attr', v.version,
                       jsonb_build_object('order_id', ?::text,
                                          'phone_source', ?::text,
                                          'copy_text', v.text,
                                          'ticked_at', n.na->>'_engage_wa_at',
                                          'pre_ticked', false),
                       COALESCE(CASE WHEN n.na->>'_engage_wa_at' ~ '^\\d{4}-\\d{2}-\\d{2}T'
                                     THEN (n.na->>'_engage_wa_at')::timestamptz END, ?)
                  FROM (SELECT ?::jsonb AS na) n
                  JOIN consent_copy_versions v
                    ON v.version = n.na->>'_engage_wa_copy' AND v.channel = 'whatsapp'
                 CROSS JOIN LATERAL unnest(v.purposes) AS pur
                 WHERE n.na->>'_engage_wa_optin' = 'yes'
                   AND NOT EXISTS (SELECT 1 FROM consents x
                                    WHERE x.identity_id = ? AND x.channel = 'whatsapp'
                                      AND x.purpose = pur AND x.source = 'cart_attr'
                                      AND x.evidence->>'order_id' = ?::text)""",
                identityId, orderId, phoneSource, orderCreatedAt, noteAttributesJson, identityId, orderId);

        if (n == 0) warnIfUnregisteredCopy(c, noteAttributesJson, orderId);
        return n;
    }

    /**
     * Order and delivery updates on WhatsApp, on the basis of the checkout
     * notice ({@code checkout_notice_v1}: the phone field says the number gets
     * order updates on WhatsApp/SMS). DPDP s.7(a): a number given for an order
     * may be used for that order. Transactional only; marketing always needs a
     * tap (cart checkbox, Thank you page).
     *
     * <p>Granted only when this person has no WhatsApp transactional record at
     * all: a STOP (withdrawn) is never overridden by a later order, and an
     * existing grant is not repeated. The caller decides whether the notice was
     * live when the order was placed (CHECKOUT_NOTICE_SINCE).
     *
     * @return rows granted (0 or 1)
     */
    public int grantWhatsAppFromCheckoutNotice(Connection c, UUID identityId, String orderId, String phoneSource,
                                               OffsetDateTime orderCreatedAt) throws SQLException {
        return Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, copy_version, evidence, occurred_at)
                SELECT ?, 'whatsapp', 'transactional', 'granted', 'checkout_notice', v.version,
                       jsonb_build_object('order_id', ?::text, 'phone_source', ?::text, 'copy_text', v.text,
                                          'basis', 'dpdp_s7a_order_updates', 'pre_ticked', false),
                       ?
                  FROM consent_copy_versions v
                 WHERE v.version = 'checkout_notice_v1' AND v.channel = 'whatsapp'
                   AND NOT EXISTS (SELECT 1 FROM consents x
                                    WHERE x.identity_id = ? AND x.channel = 'whatsapp'
                                      AND x.purpose = 'transactional')""",
                identityId, orderId, phoneSource, orderCreatedAt, identityId);
    }

    /**
     * Serialises the two writers of one order's pending opt-ins: the order
     * webhook applying them and the Thank you page recording one. Held to the
     * end of the transaction, so whichever commits second sees the other's row.
     */
    public static void lockOrder(Connection c, String orderId) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended('order:' || ?, 0))", orderId);
             var rs = ps.executeQuery()) {
            rs.next();
        }
    }

    /** Thank-you-page opt-ins that arrived before the order webhook (phase-2 §6.2). */
    public int applyPendingOptIns(Connection c, UUID identityId, String orderId) throws SQLException {
        lockOrder(c, orderId);
        int n = Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, copy_version, evidence, occurred_at)
                SELECT ?, p.channel, pur, 'granted', 'thank_you', v.version,
                       jsonb_build_object('order_id', p.order_id, 'copy_text', v.text,
                                          'clicked_at', p.received_at, 'pre_ticked', false),
                       p.received_at
                  FROM pending_optins p
                  JOIN consent_copy_versions v ON v.version = p.copy_version AND v.channel = p.channel
                 CROSS JOIN LATERAL unnest(v.purposes) AS pur
                 WHERE p.order_id = ? AND p.applied_at IS NULL""",
                identityId, orderId);
        Sql.update(c, "UPDATE pending_optins SET applied_at = now() WHERE order_id = ? AND applied_at IS NULL",
                orderId);
        return n;
    }

    /**
     * Mirrors Shopify's native "Email me with news and offers".
     *
     * <p>Written at {@code statedAt} (Shopify's consent_updated_at, else the
     * order/customer timestamp). Skipped when this exact statement was already
     * recorded (replay), or when the state in force at that moment was already
     * the same (so repeated customers/update webhooks do not bloat the ledger).
     */
    public void syncShopifyEmailConsent(Connection c, UUID identityId, String shopifyState, String ref,
                                        OffsetDateTime statedAt) throws SQLException {
        String state = switch (shopifyState == null ? "" : shopifyState) {
            case "subscribed" -> "granted";
            case "unsubscribed", "redacted" -> "withdrawn";
            default -> null;                  // not_subscribed / pending / invalid: no statement either way
        };
        if (state == null || identityId == null || statedAt == null) return;
        var statementRef = ref + "@" + statedAt.toInstant();
        Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, evidence, occurred_at)
                SELECT ?, 'email', 'marketing', ?::consent_state, 'shopify_checkout',
                       jsonb_build_object('shopify_state', ?::text, 'ref', ?::text), ?
                 WHERE NOT EXISTS (SELECT 1 FROM consents x
                                    WHERE x.identity_id = ? AND x.channel = 'email' AND x.purpose = 'marketing'
                                      AND x.evidence->>'ref' = ?::text)
                   AND (SELECT x.state FROM consents x
                         WHERE x.identity_id = ? AND x.channel = 'email' AND x.purpose = 'marketing'
                           AND x.occurred_at <= ?
                         ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1)
                       IS DISTINCT FROM ?::consent_state""",
                identityId, state, shopifyState, statementRef, statedAt,
                identityId, statementRef,
                identityId, statedAt, state);
    }

    private void warnIfUnregisteredCopy(Connection c, String noteAttributesJson, String orderId) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT n.na->>'_engage_wa_copy'
                  FROM (SELECT ?::jsonb AS na) n
                 WHERE n.na->>'_engage_wa_optin' = 'yes'
                   AND NOT EXISTS (SELECT 1 FROM consent_copy_versions v
                                    WHERE v.version = n.na->>'_engage_wa_copy' AND v.channel = 'whatsapp')""",
                noteAttributesJson);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                LOG.warn("order {}: WhatsApp opt-in ticked with copy version '{}' that is not registered in "
                        + "consent_copy_versions. No consent granted. Register the exact wording.",
                        orderId, rs.getString(1));
            }
        }
    }
}
