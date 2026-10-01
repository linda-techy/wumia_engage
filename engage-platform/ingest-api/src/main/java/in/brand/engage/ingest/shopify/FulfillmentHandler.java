package in.brand.engage.ingest.shopify;

import in.brand.engage.ingest.courier.ShipmentStatus;
import in.brand.engage.ingest.courier.Shipments;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * {@code fulfillments/create} and {@code fulfillments/update} (P1-T02): which
 * order a parcel belongs to, its AWB (tracking number) and, when the shipping
 * app posts them to Shopify, courier states. A fulfillment with a tracking
 * number emits {@code order_shipped} once. A cancelled fulfillment cancels the
 * shipment.
 */
@Singleton
public class FulfillmentHandler {

    private final Shipments shipments;

    public FulfillmentHandler(Shipments shipments) {
        this.shipments = shipments;
    }

    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("shopify_fulfillment.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var fulfillmentId = rs.getString("fulfillment_id");
            var orderId = rs.getString("order_id");
            var awb = rs.getString("awb");
            var carrier = rs.getString("carrier");
            var at = Sql.timestamp(rs, "updated_at");
            if (at == null) at = Sql.timestamp(rs, "created_at");
            var fstatus = rs.getString("status");
            var sstatus = rs.getString("shipment_status");

            var status = "cancelled".equals(fstatus) ? ShipmentStatus.CANCELLED : fromShopify(sstatus);
            long id = shipments.apply(c, new Shipments.Report(awb, carrier == null ? null : carrier.toLowerCase(),
                    orderId, fulfillmentId, rs.getString("tracking_url"), status, at,
                    "shopify:" + fulfillmentId + ":" + (status == null ? "none" : status.db()) + ":"
                            + (at == null ? "" : at.toInstant()),
                    null, status == ShipmentStatus.NDR ? "shopify: " + sstatus : null));

            if (awb != null && "success".equals(fstatus)) shipments.shipped(c, id, orderId, awb, carrier);
        }
    }

    /** Shopify's fulfillment shipment_status; label and pickup states change nothing. */
    static ShipmentStatus fromShopify(String s) {
        if (s == null) return null;
        return switch (s) {
            case "in_transit", "picked_up" -> ShipmentStatus.IN_TRANSIT;
            case "out_for_delivery" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "attempted_delivery", "failure" -> ShipmentStatus.NDR;
            case "delivered" -> ShipmentStatus.DELIVERED;
            default -> null;       // label_printed, label_purchased, confirmed, ready_for_pickup
        };
    }
}
