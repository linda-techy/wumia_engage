package in.brand.engage.worker;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nightly push token lifecycle (phase-2 §9, P2-T08): a device neither
 * refreshed nor clicked for 180 days is deactivated, reason {@code dormant}.
 *
 * <p>Stale (30 days) is not decided here: it is the {@code device_health}
 * view, and stale tokens stay active for back-in-stock. Rotation, FCM
 * rejections and "turn off" deactivate elsewhere, as they happen.
 *
 * <p>02:30 IST. Several workers may be scheduled; a transaction-scoped
 * advisory lock lets one of them do the pass and the others skip it.
 */
@Singleton
public class TokenLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(TokenLifecycle.class);
    static final Duration DORMANT = Duration.ofDays(180);

    private final Db db;
    private final Clock clock;

    public TokenLifecycle(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** @return devices deactivated, or -1 when another worker holds the pass */
    public int deactivateDormant() {
        var cutoff = clock.instant().minus(DORMANT).atOffset(ZoneOffset.UTC);
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT pg_try_advisory_xact_lock(hashtextextended('token_lifecycle', 0))");
                 var rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) return -1;
            }
            return Sql.update(c, """
                    UPDATE devices SET active = false, deactivated_reason = 'dormant'
                     WHERE active AND last_refreshed_at < ?
                       AND (last_clicked_at IS NULL OR last_clicked_at < ?)""", cutoff, cutoff);
        });
    }

    @Singleton
    @Requires(property = "engage.worker.jobs-enabled", value = "true", defaultValue = "true")
    static class Nightly {
        private final TokenLifecycle lifecycle;

        Nightly(TokenLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Scheduled(cron = "0 30 2 * * *", zoneId = "Asia/Kolkata")
        void run() {
            try {
                int n = lifecycle.deactivateDormant();
                if (n >= 0) LOG.info("token lifecycle: {} dormant devices deactivated", n);
            } catch (RuntimeException e) {
                LOG.error("token lifecycle pass failed", e);
            }
        }
    }
}
