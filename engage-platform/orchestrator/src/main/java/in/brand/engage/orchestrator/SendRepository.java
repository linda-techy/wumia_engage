package in.brand.engage.orchestrator;

import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.TokenPrune;
import in.brand.engage.channels.push.FcmTokenPruner;
import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigSnapshot;
import in.brand.engage.policy.Decision;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code sends} table. Every attempt is a row, blocked and deferred ones
 * included (CLAUDE.md invariant 3), each with the config snapshot it was
 * decided under (invariant 4).
 *
 * <p>The router's two transactions are here: {@code record*} before the
 * provider call, {@code mark*} after it. Nothing here runs during the call.
 */
@Singleton
public class SendRepository {

    /** A queued row older than this was lost between the two transactions. */
    public static final java.time.Duration LOST_AFTER = java.time.Duration.ofMinutes(10);

    public record Existing(long id, String status) {}

    private final Db db;
    private final FcmTokenPruner pruner;
    private final Clock clock;

    public SendRepository(Db db, FcmTokenPruner pruner, Clock clock) {
        this.db = db;
        this.pruner = pruner;
        this.clock = clock;
    }

    public Optional<Existing> findByKey(String idempotencyKey) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT id, status::text FROM sends WHERE idempotency_key = ?", idempotencyKey);
                 var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Existing(rs.getLong(1), rs.getString(2))) : Optional.<Existing>empty();
            }
        });
    }

    /** @return the new row's id, or empty if the key was taken concurrently */
    public Optional<Long> recordBlocked(SendCommand cmd, Category category, Decision.Block block) {
        var detail = new LinkedHashMap<String, Object>(block.detail());
        detail.put("reason", block.reason().name());
        return db.inTx(c -> insert(c, cmd, cmd.idempotencyKey(), category, "blocked", detail, block.configSnapshotId()));
    }

    /**
     * A deferral takes the key {@code <key>#defer:<n>}, never the real key, so
     * the retry after the window opens is not swallowed as a duplicate (invariant 5).
     */
    public long recordDeferred(SendCommand cmd, Category category, Decision.Defer defer) {
        var detail = Map.<String, Object>of("reason", defer.reason().name(), "until", defer.until().toString());
        return db.inTx(c -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                long n;
                try (var ps = Sql.prepare(c, """
                        SELECT count(*) FROM sends WHERE starts_with(idempotency_key, ? || '#defer:')""",
                        cmd.idempotencyKey());
                     var rs = ps.executeQuery()) {
                    rs.next();
                    n = rs.getLong(1) + 1;
                }
                var id = insert(c, cmd, cmd.idempotencyKey() + "#defer:" + n, category, "deferred", detail,
                        defer.configSnapshotId());
                if (id.isPresent()) return id.get();
            }
            throw new IllegalStateException("could not record a deferral for " + cmd.idempotencyKey());
        });
    }

    /** @return the queued row's id (the push {@code sid}), or empty if the key was taken concurrently */
    public Optional<Long> recordQueued(SendCommand cmd, Decision.Allow allow) {
        var detail = Map.<String, Object>of(
                "effective_category", allow.effectiveCategory().dbName(),
                "unit_cost_paise", allow.unitCostPaise(),
                "free_window", allow.freeWindow(),
                "push_targets", allow.addresses().push().size());
        return db.inTx(c -> insert(c, cmd, cmd.idempotencyKey(), allow.effectiveCategory(), "queued", detail,
                allow.configSnapshotId()));
    }

    /** Step 3 after an accepted send: mark sent, book spend (not WhatsApp), prune dead tokens. */
    public void markSent(long sendId, Channel channel, Category category, long unitCostPaise, DispatchResult result) {
        db.inTx(c -> {
            // Also corrects a row the sweeper wrongly gave up on while the provider call was slow.
            Sql.update(c, """
                    UPDATE sends SET status = 'sent', sent_at = now(), provider_id = ?, delivered_count = ?,
                                     cost_paise = ?, failed_reason = NULL
                     WHERE id = ? AND (status = 'queued' OR failed_reason = 'lost_in_flight')""",
                    result.providerId(), result.delivered(), Math.toIntExact(unitCostPaise), sendId);
            // WhatsApp is booked from the status webhook, which carries the category Meta billed.
            if (channel != Channel.WHATSAPP && unitCostPaise > 0) {
                Sql.update(c, """
                        INSERT INTO spend_ledger (day, channel, category, messages, paise)
                        VALUES (CAST(? AS date), CAST(? AS channel), CAST(? AS msg_category), 1, ?)
                        ON CONFLICT (day, channel, category) DO UPDATE
                           SET messages = spend_ledger.messages + 1, paise = spend_ledger.paise + EXCLUDED.paise""",
                        LocalDate.ofInstant(clock.instant(), ConfigSnapshot.IST).toString(),
                        channel.dbName(), category.dbName(), unitCostPaise);
            }
            prune(c, result.prunes());
            return null;
        });
    }

    /**
     * Step 3 after a refused send. A suppression is added only when the
     * provider blamed the recipient ({@code suppress}); never for our own bugs.
     */
    public void markFailed(long sendId, java.util.UUID identityId, Channel channel, String code, boolean suppress,
                           List<TokenPrune> prunes) {
        db.inTx(c -> {
            Sql.update(c, "UPDATE sends SET status = 'failed', failed_reason = ? WHERE id = ? AND status = 'queued'",
                    code, sendId);
            if (suppress) {
                Sql.update(c, """
                        INSERT INTO suppressions (identity_id, channel, reason)
                        VALUES (?, CAST(? AS channel), ?) ON CONFLICT DO NOTHING""",
                        identityId, channel.dbName(), "provider_" + code);
            }
            prune(c, prunes);
            return null;
        });
    }

    /**
     * Queued rows older than {@code LOST_AFTER} were lost between the router's
     * transactions (a crash mid-send). Mark them failed; never re-send, because
     * the provider may have delivered.
     */
    public int sweepLostInFlight() {
        var before = clock.instant().minus(LOST_AFTER);
        return db.inTx(c -> Sql.update(c, """
                UPDATE sends SET status = 'failed', failed_reason = 'lost_in_flight'
                 WHERE status = 'queued' AND created_at < ?""", before.atOffset(ZoneOffset.UTC)));
    }

    private void prune(Connection c, List<TokenPrune> prunes) throws SQLException {
        if (!prunes.isEmpty()) pruner.apply(c, prunes);
    }

    private Optional<Long> insert(Connection c, SendCommand cmd, String key, Category category, String status,
                                  Map<String, Object> decision, long snapshotId) throws SQLException {
        var keys = decision.keySet().toArray(String[]::new);
        var values = decision.values().stream().map(String::valueOf).toArray(String[]::new);
        try (var ps = Sql.prepare(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, intent_key, step_index,
                                   idempotency_key, status, decision, config_snapshot_id, cascade_run_id, created_at)
                VALUES (?, CAST(? AS channel), CAST(? AS msg_category), ?, ?, ?, ?, CAST(? AS send_status),
                        jsonb_object(CAST(? AS text[]), CAST(? AS text[])), ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id""",
                cmd.identityId(), cmd.channel().dbName(), category.dbName(), cmd.templateKey(), cmd.intentKey(),
                cmd.stepIndex(), key, status, keys, values, snapshotId, cmd.cascadeRunId(),
                clock.instant().atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }
}
