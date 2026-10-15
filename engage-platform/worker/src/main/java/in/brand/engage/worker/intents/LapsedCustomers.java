package in.brand.engage.worker.intents;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds customers who have gone quiet (P5-T08) and emits {@code became_lapsed}
 * once per lapse, for {@link Winback}. Run nightly by the worker.
 *
 * <ul>
 * <li>Lapsed: the last non-cancelled order is {@code journey.winback.lapsed_days}
 *     old. Only customers who crossed that line in the last 7 days are picked:
 *     a missed night is caught up, and the first run after a deploy does not
 *     message everyone who lapsed years ago.</li>
 * <li>Not visiting: no storefront activity (browsing linked to them, cart,
 *     checkout, push click, WhatsApp reply) for {@code journey.winback.no_visit_days}.
 *     Someone still browsing gets the browse and cart journeys instead.</li>
 * <li>Merged identities are skipped. The dedupe key holds the last order's
 *     time, so a new order and a new lapse give a new win-back.</li>
 * </ul>
 */
@Singleton
public class LapsedCustomers {

    private static final Logger LOG = LoggerFactory.getLogger(LapsedCustomers.class);
    static final Duration CATCH_UP = Duration.ofDays(7);

    private final Db db;
    private final ConfigResolver config;

    public LapsedCustomers(Db db, ConfigResolver config) {
        this.db = db;
        this.config = config;
    }

    /** @return the {@code became_lapsed} events written (duplicates of earlier nights are not counted) */
    public int detect(Instant now) {
        var snapshot = config.snapshot();
        var lapsedBefore = utc(now.minus(Duration.ofDays(snapshot.longValue("journey.winback.lapsed_days", "*"))));
        var visitSince = utc(now.minus(Duration.ofDays(snapshot.longValue("journey.winback.no_visit_days", "*"))));
        return db.inTx(c -> {
            int n = 0;
            try (var ps = Sql.prepare(c, """
                    WITH last AS (
                      SELECT o.identity_id, max(o.created_at) AS last_order_at
                        FROM orders o
                       WHERE o.identity_id IS NOT NULL AND o.cancelled_at IS NULL
                       GROUP BY o.identity_id
                      HAVING max(o.created_at) <= ? AND max(o.created_at) > ?)
                    SELECT l.identity_id, l.last_order_at
                      FROM last l JOIN identities i ON i.id = l.identity_id AND i.merged_into IS NULL
                     WHERE NOT EXISTS (
                             SELECT 1 FROM events e
                              WHERE e.identity_id = l.identity_id AND e.occurred_at > ?
                                AND e.name IN ('cart_updated', 'checkout_updated', 'push_clicked', 'push_subscribed',
                                               'wa_message_received', 'order_placed'))
                       AND NOT EXISTS (
                             SELECT 1 FROM identity_keys k
                               JOIN events e ON e.source = 'pixel' AND e.props->>'anon_id' = k.value
                              WHERE k.identity_id = l.identity_id AND k.kind = 'anon' AND e.occurred_at > ?)""",
                    lapsedBefore, utc(lapsedBefore.toInstant().minus(CATCH_UP)), visitSince, visitSince);
                 var rs = ps.executeQuery()) {
                var found = new java.util.ArrayList<Object[]>();
                while (rs.next()) found.add(new Object[] {rs.getObject(1, UUID.class), Sql.timestamp(rs, "last_order_at")});
                for (var row : found) {
                    var identity = (UUID) row[0];
                    var last = ((OffsetDateTime) row[1]).toInstant();
                    // Stamped with the check's time (the journey sends later that morning); one row per lapse.
                    n += Sql.update(c, """
                            INSERT INTO events (identity_id, name, props, source, dedupe_key, occurred_at)
                            VALUES (?, 'became_lapsed', jsonb_build_object('last_order_at', ?::text), 'worker', ?, ?)
                            ON CONFLICT (dedupe_key) DO NOTHING""",
                            identity, last.toString(), "lapsed:" + identity + ":" + last, utc(now));
                }
            }
            if (n > 0) LOG.info("win-back: {} customers lapsed", n);
            return n;
        });
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
