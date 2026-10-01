package in.brand.engage.admin.ingest;

import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/ingest/health}: the webhook inbox by source and topic, and
 * the items stuck after failed attempts. Read-only. Requires VIEWER.
 */
@Controller("/api/ingest")
@ExecuteOn(TaskExecutors.BLOCKING)
public class IngestHealthController {

    @Serdeable
    public record TopicHealth(String source, String topic, long total, long pending, long failed, long dead,
                              long oldestPendingAgeSeconds, OffsetDateTime lastReceivedAt) {}

    @Serdeable
    public record StuckItem(String source, String topic, String deliveryId, int attempts, String lastError,
                            OffsetDateTime receivedAt, OffsetDateTime nextAttemptAt) {}

    @Serdeable
    public record IngestHealth(List<TopicHealth> topics, List<StuckItem> stuck) {}

    private final Db db;
    private final CurrentOperator current;

    public IngestHealthController(Db db, CurrentOperator current) {
        this.db = db;
        this.current = current;
    }

    @Get("/health")
    public IngestHealth health() {
        current.require("VIEWER");
        return db.inTx(c -> {
            var topics = new ArrayList<TopicHealth>();
            try (var ps = Sql.prepare(c, SqlFiles.get("ingest_health.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    topics.add(new TopicHealth(rs.getString("source"), rs.getString("topic"), rs.getLong("total"),
                            rs.getLong("pending"), rs.getLong("failed"), rs.getLong("dead"),
                            rs.getLong("oldest_pending_age_seconds"), Sql.timestamp(rs, "last_received_at")));
                }
            }
            var stuck = new ArrayList<StuckItem>();
            try (var ps = Sql.prepare(c, SqlFiles.get("ingest_stuck.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    stuck.add(new StuckItem(rs.getString("source"), rs.getString("topic"), rs.getString("delivery_id"),
                            rs.getInt("attempts"), rs.getString("last_error"), Sql.timestamp(rs, "received_at"),
                            Sql.timestamp(rs, "next_attempt_at")));
                }
            }
            return new IngestHealth(topics, stuck);
        });
    }
}
