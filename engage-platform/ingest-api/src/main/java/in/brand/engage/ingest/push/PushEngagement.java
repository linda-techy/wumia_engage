package in.brand.engage.ingest.push;

import in.brand.engage.core.crypto.BeaconSignature;
import in.brand.engage.ingest.push.StorefrontRequests.Engagement;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Impression and click beacons from the service worker, joined to the send
 * that caused them (P3-T08).
 *
 * <p>Only a beacon whose {@code sig} matches its {@code sid} is recorded:
 * send ids are sequential, and an unsigned click would let anyone mark other
 * people's pushes clicked and so end their cascades. Anything else is
 * acknowledged and dropped.
 *
 * <p>A click sets {@code sends.clicked_at} once (the first click wins) and
 * writes a {@code push_clicked} event; the worker turns that into the
 * cascade's success signal. ingest-api never touches the orchestrator.
 */
@Singleton
public class PushEngagement {

    private static final Logger LOG = LoggerFactory.getLogger(PushEngagement.class);

    public enum Result { RECORDED, IGNORED }

    private final Db db;
    private final EventWriter events;
    private final BeaconSignature beacons;

    public PushEngagement(Db db, EventWriter events, @Value("${engage.shopify.api-secret:}") String appSecret) {
        this.db = db;
        this.events = events;
        this.beacons = BeaconSignature.fromAppSecret(appSecret);
    }

    public Result record(Engagement e) {
        long sendId;
        try {
            sendId = Long.parseLong(e.sid());
        } catch (NumberFormatException | NullPointerException ex) {
            return Result.IGNORED;                          // pre-P3 pushes and test pushes carry no sid
        }
        if (!beacons.verify(sendId, e.sig())) {
            LOG.debug("unsigned or forged {} beacon for send {}", e.kind(), sendId);
            return Result.IGNORED;
        }
        return "click".equals(e.kind()) ? click(sendId) : impression(sendId);
    }

    private Result click(long sendId) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    UPDATE sends SET clicked_at = now(),
                                     status = CASE WHEN status IN ('sent','delivered','read') THEN 'clicked'::send_status
                                                   ELSE status END
                     WHERE id = ? AND channel = 'push' AND clicked_at IS NULL
                    RETURNING identity_id, cascade_run_id, intent_key""", sendId);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return Result.IGNORED;       // a second click, or not a push send
                var runId = rs.getLong("cascade_run_id");
                boolean hasRun = !rs.wasNull();
                events.write(c, rs.getObject("identity_id", UUID.class), "push_clicked", "storefront",
                        "push_clicked:" + sendId,
                        hasRun ? Map.of("send_id", sendId, "cascade_run_id", runId, "intent_key", String.valueOf(rs.getString("intent_key")))
                               : Map.of("send_id", sendId, "intent_key", String.valueOf(rs.getString("intent_key"))));
                return Result.RECORDED;
            }
        });
    }

    /** The service worker showed it: the push reached the device. Success for push is the click, not this. */
    private Result impression(long sendId) {
        int n = db.inTx(c -> Sql.update(c, """
                UPDATE sends SET delivered_at = COALESCE(delivered_at, now()),
                                 status = CASE WHEN status = 'sent' THEN 'delivered'::send_status ELSE status END
                 WHERE id = ? AND channel = 'push'""", sendId));
        return n > 0 ? Result.RECORDED : Result.IGNORED;
    }
}
