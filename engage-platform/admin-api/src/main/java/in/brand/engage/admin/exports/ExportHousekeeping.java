package in.brand.engage.admin.exports;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Expires exports after 24 h and deletes their files. The export row and its audit trail stay. */
@Singleton
public class ExportHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(ExportHousekeeping.class);

    private final Db db;

    public ExportHousekeeping(Db db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = "15m", initialDelay = "1m")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            LOG.error("export purge failed", e);
        }
    }

    /** @return exports expired by this pass */
    public int purge() {
        return db.inTx(c -> {
            int expired = Sql.update(c, "UPDATE exports SET status = 'expired' WHERE status = 'ready' AND expires_at <= now()");
            Sql.update(c, "DELETE FROM export_files f USING exports e WHERE e.id = f.export_id AND e.status = 'expired'");
            return expired;
        });
    }
}
