package in.brand.engage.ingest.meta;

import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Replies to a failed-delivery WhatsApp become support requests for staff
 * (owner's decision 2026-10-15: no automatic courier update). A message from
 * a customer within 72 h of their {@code ndr_raised_v1} message (the one it
 * quotes, else the latest) opens a {@code delivery} request for that parcel,
 * with the reply text; later messages are appended to the open request. The
 * first emits {@code delivery_reply}, which the worker acknowledges.
 *
 * <p>Only these replies keep their text; every other inbound message is
 * stored as ids only (P4-T04).
 */
@Singleton
public class DeliveryReplies {

    static final int WINDOW_HOURS = 72;
    static final int TEXT_MAX = 2000;

    private final EventWriter events;

    public DeliveryReplies(EventWriter events) {
        this.events = events;
    }

    /** @return true if the message was a delivery reply and was recorded */
    public boolean record(Connection c, UUID identity, String messageId, String text, String contextWamid, Instant at)
            throws SQLException {
        Long shipmentId = null;
        String orderId = null;
        try (var ps = Sql.prepare(c, """
                SELECT NULLIF(split_part(r.subject_key, ':', 2), '')::bigint AS shipment_id, sh.order_id
                  FROM sends s
                  JOIN cascade_runs r ON r.id = s.cascade_run_id
                  LEFT JOIN shipments sh ON sh.id = NULLIF(split_part(r.subject_key, ':', 2), '')::bigint
                 WHERE s.identity_id = ? AND s.channel = 'whatsapp' AND s.template_key = 'ndr_raised_v1'
                   AND s.status IN ('sent', 'delivered', 'read') AND s.created_at > ?
                   AND r.subject_key LIKE 'shipment:%'
                 ORDER BY (s.provider_id = ?) DESC, s.created_at DESC
                 LIMIT 1""", identity, at.minusSeconds(WINDOW_HOURS * 3600L).atOffset(ZoneOffset.UTC),
                contextWamid == null ? "" : contextWamid);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return false;
            shipmentId = Sql.nullableLong(rs, "shipment_id");
            orderId = rs.getString("order_id");
        }
        var body = text == null || text.isBlank() ? "[a message that is not text]" : text.strip();
        if (body.length() > TEXT_MAX) body = body.substring(0, TEXT_MAX);

        int appended = Sql.update(c, """
                UPDATE support_requests
                   SET message_text = concat_ws(E'\\n', message_text, ?), message_ids = array_append(message_ids, ?),
                       updated_at = now()
                 WHERE shipment_id = ? AND kind = 'delivery' AND status = 'open' AND NOT (? = ANY(message_ids))""",
                body, messageId, shipmentId, messageId);
        if (appended > 0) return true;
        try (var ps = Sql.prepare(c, """
                INSERT INTO support_requests (identity_id, kind, order_id, shipment_id, message_text, message_ids)
                VALUES (?, 'delivery', ?, ?, ?, ARRAY[?]::text[])
                ON CONFLICT (shipment_id) WHERE status = 'open' AND kind = 'delivery' DO NOTHING
                RETURNING id""", identity, orderId, shipmentId, body, messageId);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return true;                                        // a concurrent open request took it
            events.write(c, identity, "delivery_reply", "meta", "delivery_reply:" + messageId,
                    Map.of("support_request_id", rs.getLong(1), "message_id", messageId));
            return true;
        }
    }
}
