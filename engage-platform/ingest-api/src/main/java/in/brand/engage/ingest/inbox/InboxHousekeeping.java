package in.brand.engage.ingest.inbox;

import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Webhook inbox housekeeping (P1-T05).
 *
 * <ul>
 * <li>Every 15 s: the pending, oldest-pending and dead-letter gauges, from the database.</li>
 * <li>03:00 IST: delete rows processed more than 7 days ago, in batches. Unprocessed
 *     rows, dead letters included, are never deleted. One runner across pods via a
 *     transaction-scoped advisory lock.</li>
 * </ul>
 * Dead letters are logged by {@code InboxProcessor} at the moment they fail the last time.
 */
@Singleton
public class InboxHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(InboxHousekeeping.class);
    static final int PURGE_BATCH = 10_000;

    private final Db db;
    private final IngestMetrics metrics;

    public InboxHousekeeping(Db db, IngestMetrics metrics) {
        this.db = db;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = "15s", initialDelay = "5s")
    public void refreshGauges() {
        try {
            db.inTx(c -> {
                try (var ps = Sql.prepare(c, """
                        SELECT count(*) FILTER (WHERE attempts < ?),
                               COALESCE(extract(epoch FROM now() - min(received_at) FILTER (WHERE attempts < ?)), 0)::bigint,
                               count(*) FILTER (WHERE attempts >= ?)
                          FROM webhook_inbox WHERE processed_at IS NULL""",
                        InboxRepository.MAX_ATTEMPTS, InboxRepository.MAX_ATTEMPTS, InboxRepository.MAX_ATTEMPTS);
                     var rs = ps.executeQuery()) {
                    rs.next();
                    metrics.inbox(rs.getLong(1), rs.getLong(2), rs.getLong(3));
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("inbox gauge refresh failed", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zoneId = "Asia/Kolkata")
    void nightly() {
        try {
            int n = purgeProcessed();
            if (n >= 0) LOG.info("inbox housekeeping: {} processed rows older than 7 days deleted", n);
        } catch (RuntimeException e) {
            LOG.error("inbox housekeeping failed", e);
        }
    }

    /** @return rows deleted, or -1 when another pod holds the pass */
    public int purgeProcessed() {
        int total = 0;
        while (true) {
            int n = db.inTx(c -> {
                try (var ps = Sql.prepare(c, "SELECT pg_try_advisory_xact_lock(hashtextextended('inbox_housekeeping', 0))");
                     var rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) return -1;
                }
                // received_at first so the age index is used; processed_at is the rule.
                return Sql.update(c, """
                        DELETE FROM webhook_inbox WHERE ctid IN (
                          SELECT ctid FROM webhook_inbox
                           WHERE processed_at IS NOT NULL
                             AND received_at < now() - interval '7 days'
                             AND processed_at < now() - interval '7 days'
                           LIMIT ?)""", PURGE_BATCH);
            });
            if (n < 0) return total == 0 ? -1 : total;
            total += n;
            if (n < PURGE_BATCH) return total;
        }
    }
}
