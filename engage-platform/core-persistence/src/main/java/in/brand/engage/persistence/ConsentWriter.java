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

    /** Thank-you-page opt-ins that arrived before the order webhook (phase-2 §6.2). */
    public int applyPendingOptIns(Connection c, UUID identityId, String orderId) throws SQLException {
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
