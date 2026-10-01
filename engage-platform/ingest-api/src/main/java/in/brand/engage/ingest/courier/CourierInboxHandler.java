package in.brand.engage.ingest.courier;

import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Courier aggregator webhooks from the inbox ({@code source = 'courier'}) into
 * {@link Shipments}. The adapter maps the aggregator's status and time; a
 * status that changes nothing (manifested, pickup scheduled) is acknowledged
 * and dropped. The dedupe key is AWB + status + courier time, since the
 * aggregator sends no event id.
 */
@Singleton
public class CourierInboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CourierInboxHandler.class);

    private final CourierAdapter adapter;
    private final Shipments shipments;

    public CourierInboxHandler(CourierAdapter adapter, Shipments shipments) {
        this.adapter = adapter;
        this.shipments = shipments;
    }

    @Override
    public String source() {
        return "courier";
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("courier_event.sql"), item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            var awb = rs.getString("awb");
            var label = rs.getString("status_label");
            if (awb == null) {
                LOG.warn("courier {} without an AWB; ignored", item.deliveryId());
                return;
            }
            var status = adapter.status(label, rs.getString("status_code"));
            if (status == null) {
                LOG.info("courier {} AWB {} status '{}' changes nothing", item.deliveryId(), awb, label);
                return;
            }
            var at = adapter.time(rs.getString("status_time"));
            if (at == null) at = adapter.time(rs.getString("last_scan_time"));
            if (at == null) {
                LOG.warn("courier {} AWB {}: no readable event time; ignored", item.deliveryId(), awb);
                return;
            }
            shipments.apply(c, new Shipments.Report(awb, adapter.name(), null, null, null, status, at,
                    adapter.name() + ":" + awb + ":" + status.db() + ":" + at.toInstant(),
                    rs.getString("summary"), status == ShipmentStatus.NDR ? rs.getString("last_activity") : null));
        }
    }
}
