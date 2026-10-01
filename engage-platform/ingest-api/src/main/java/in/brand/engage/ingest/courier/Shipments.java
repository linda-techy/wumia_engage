package in.brand.engage.ingest.courier;

import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Shipment state from two sources: Shopify fulfillments (who, which order) and
 * the courier aggregator (where the parcel is). Either can arrive first
 * (invariant 11), so both go through here.
 *
 * <ul>
 * <li>A shipment is found by AWB (any carrier), else by Shopify fulfillment id,
 *     else created. Both writers take the same per-AWB lock, so they cannot
 *     create two rows for one parcel.</li>
 * <li>Every source event is recorded once in {@code shipment_events}; a replay
 *     changes nothing.</li>
 * <li>Status moves by the courier's time ({@code status_at}), never arrival:
 *     a late "in transit" cannot overwrite "delivered". Same time → the later
 *     stage wins. A terminal status (delivered, RTO, cancelled) is never left.</li>
 * <li>Each change to out for delivery, delivered or NDR writes one event
 *     ({@code out_for_delivery}, {@code order_delivered}, {@code delivery_failed})
 *     with the order and AWB; every NDR attempt counts.</li>
 * </ul>
 */
@Singleton
public class Shipments {

    /** One status report about one parcel. */
    public record Report(String awb, String carrier, String orderId, String fulfillmentId, String trackingUrl,
                         ShipmentStatus status, OffsetDateTime at, String sourceEvent, String rawJson,
                         String ndrReason) {}

    private final EventWriter events;

    public Shipments(EventWriter events) {
        this.events = events;
    }

    /** @return the shipment id */
    public long apply(Connection c, Report r) throws SQLException {
        if (r.awb() != null) {
            try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended('awb:' || ?, 0))", r.awb());
                 var rs = ps.executeQuery()) {
                rs.next();
            }
        }
        long id = findOrCreate(c, r);

        // Fill in what this source knows and the row lacks (a courier-first row has no order yet).
        Sql.update(c, """
                UPDATE shipments SET order_id = COALESCE(order_id, ?), fulfillment_id = COALESCE(fulfillment_id, ?),
                                     awb = COALESCE(awb, ?), tracking_url = COALESCE(?, tracking_url)
                 WHERE id = ?""", r.orderId(), r.fulfillmentId(), r.awb(), r.trackingUrl(), id);

        if (r.status() == null || r.at() == null) return id;
        int recorded = Sql.update(c, """
                INSERT INTO shipment_events (shipment_id, source_event, status, occurred_at, raw)
                VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING""",
                id, r.sourceEvent(), r.status().db(), r.at(), r.rawJson() == null ? "{}" : r.rawJson());
        if (recorded == 0) return id;                                  // replay

        ShipmentStatus current;
        OffsetDateTime currentAt;
        String orderId;
        try (var ps = Sql.prepare(c, "SELECT status, status_at, order_id FROM shipments WHERE id = ? FOR UPDATE", id);
             var rs = ps.executeQuery()) {
            rs.next();
            current = ShipmentStatus.fromDb(rs.getString("status"));
            currentAt = Sql.timestamp(rs, "status_at");
            orderId = rs.getString("order_id");
        }
        if (current.terminal()) return id;
        boolean newer = r.at().isAfter(currentAt)
                || (r.at().isEqual(currentAt) && r.status().rank() > current.rank());
        boolean anotherAttempt = r.status() == ShipmentStatus.NDR && r.at().isAfter(currentAt);
        if (!newer || (r.status() == current && !anotherAttempt)) return id;

        Sql.update(c, """
                UPDATE shipments SET status = ?, status_at = ?,
                       ndr_attempts = ndr_attempts + CASE WHEN ? THEN 1 ELSE 0 END,
                       ndr_reason = CASE WHEN ? THEN ? ELSE ndr_reason END
                 WHERE id = ?""",
                r.status().db(), r.at(), r.status() == ShipmentStatus.NDR,
                r.status() == ShipmentStatus.NDR, r.ndrReason(), id);

        var name = r.status().eventName();
        if (name != null) emit(c, id, name, orderId, r);
        return id;
    }

    /** {@code order_shipped}, once per shipment, when Shopify says it left with a tracking number. */
    public void shipped(Connection c, long shipmentId, String orderId, String awb, String carrier) throws SQLException {
        var props = new LinkedHashMap<String, Object>();
        props.put("order_id", orderId);
        props.put("awb", awb);
        props.put("carrier", carrier);
        props.put("shipment_id", shipmentId);
        events.write(c, identity(c, orderId), "order_shipped", "shopify", "shipped:" + shipmentId, props);
    }

    private void emit(Connection c, long shipmentId, String name, String orderId, Report r) throws SQLException {
        var props = new LinkedHashMap<String, Object>();
        props.put("order_id", orderId);
        props.put("awb", r.awb());
        props.put("carrier", r.carrier());
        props.put("shipment_id", shipmentId);
        if (r.status() == ShipmentStatus.NDR) props.put("ndr_reason", r.ndrReason());
        events.write(c, identity(c, orderId), name, "courier",
                "shipment:" + shipmentId + ":" + r.status().db() + ":" + r.at().toInstant(), props);
    }

    private static UUID identity(Connection c, String orderId) throws SQLException {
        if (orderId == null) return null;
        try (var ps = Sql.prepare(c, "SELECT identity_id FROM orders WHERE id = ?", orderId);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private static long findOrCreate(Connection c, Report r) throws SQLException {
        if (r.awb() != null) {
            try (var ps = Sql.prepare(c, "SELECT id FROM shipments WHERE awb = ? ORDER BY id LIMIT 1", r.awb());
                 var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        if (r.fulfillmentId() != null) {
            try (var ps = Sql.prepare(c, "SELECT id FROM shipments WHERE fulfillment_id = ?", r.fulfillmentId());
                 var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        // status_at starts before any real event, so the first report always applies.
        try (var ps = Sql.prepare(c, """
                INSERT INTO shipments (order_id, fulfillment_id, awb, carrier, tracking_url, status, status_at)
                VALUES (?, ?, ?, ?, ?, 'created', '1970-01-01T00:00:00Z') RETURNING id""",
                r.orderId(), r.fulfillmentId(), r.awb(), r.carrier(), r.trackingUrl());
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
