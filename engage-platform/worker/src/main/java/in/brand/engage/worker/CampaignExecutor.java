package in.brand.engage.worker;

import in.brand.engage.orchestrator.CampaignCascades;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.MessageOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs armed campaigns (P6-T06). Each pass, per RUNNING campaign:
 *
 * <ol>
 * <li>Stop at the budget cap: spend so far, plus what this batch could cost,
 *     never passes {@code budget_cap_paise}; at the cap the campaign is PAUSED
 *     with {@code paused_reason = budget_cap}.</li>
 * <li>Take tokens from {@code campaign_rate_buckets} (capacity one batch,
 *     refilled at {@code send_rate_per_minute}), so the rate is never exceeded
 *     by more than one batch.</li>
 * <li>Claim that many {@code pending} recipients with {@code SKIP LOCKED}
 *     (pods share the work), then dispatch each through the orchestrator with
 *     intent {@code campaign:<id>}. Policy runs per send: arming is not
 *     permission.</li>
 * </ol>
 *
 * Pause is checked at the start of each batch, so a paused campaign stops
 * within one batch. A claim older than 10 minutes with no outcome (a crashed
 * pod) goes back to pending; re-dispatching is safe because a campaign run is
 * unique per (intent, identity).
 */
@Singleton
public class CampaignExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(CampaignExecutor.class);

    public static final int BATCH = 100;

    private final Db db;
    private final MessageOrchestrator orchestrator;
    private final ConfigResolver config;

    public CampaignExecutor(Db db, MessageOrchestrator orchestrator, ConfigResolver config) {
        this.db = db;
        this.orchestrator = orchestrator;
        this.config = config;
    }

    /** One pass over every runnable campaign. @return recipients dispatched */
    public int runOnce() {
        var ids = db.inTx(c -> {
            Sql.update(c, """
                    UPDATE campaigns SET status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
                     WHERE status = 'SCHEDULED' AND (scheduled_at IS NULL OR scheduled_at <= now())""");
            var out = new ArrayList<UUID>();
            try (var ps = Sql.prepare(c, "SELECT id FROM campaigns WHERE status = 'RUNNING' ORDER BY started_at");
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class));
            }
            return out;
        });
        int total = 0;
        for (var id : ids) {
            try {
                total += runBatch(id);
            } catch (RuntimeException e) {
                LOG.error("campaign {} batch failed", id, e);
            }
        }
        return total;
    }

    private record Batch(List<UUID> recipients, Map<String, String> vars) {}

    /** One batch of one campaign. @return recipients dispatched */
    int runBatch(UUID campaignId) {
        var batch = db.inTx(c -> claim(c, campaignId));
        if (batch == null) return 0;
        var intentKey = CampaignCascades.intentKey(campaignId);
        for (var identity : batch.recipients()) {
            try {
                var run = orchestrator.dispatch(new MessageIntent(identity, intentKey, identity.toString(), batch.vars(), null));
                db.inTx(c -> record(c, campaignId, identity, run.id()));
            } catch (RuntimeException e) {
                LOG.warn("campaign {} recipient dispatch failed: {}", campaignId, e.toString());
                db.inTx(c -> Sql.update(c, """
                        UPDATE campaign_recipients SET state = 'failed', block_reason = 'error', processed_at = now()
                         WHERE campaign_id = ? AND identity_id = ?""", campaignId, identity));
            }
        }
        return batch.recipients().size();
    }

    /** Budget, tokens and the claim, in one transaction holding the campaign row. Null: nothing to do now. */
    private Batch claim(Connection c, UUID id) throws SQLException {
        String channel;
        Long cap;
        Map<String, String> vars = new HashMap<>();
        try (var ps = Sql.prepare(c, """
                SELECT channel::text, budget_cap_paise FROM campaigns WHERE id = ? AND status = 'RUNNING'
                   FOR UPDATE SKIP LOCKED""", id); var rs = ps.executeQuery()) {
            if (!rs.next()) return null;                   // paused, finished, or another pod has it
            channel = rs.getString(1);
            cap = Sql.nullableLong(rs, "budget_cap_paise");
        }
        try (var ps = Sql.prepare(c, "SELECT key, value FROM campaigns, jsonb_each_text(vars) WHERE id = ?", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) vars.put(rs.getString(1), rs.getString(2));
        }

        // A crashed pod's claims go back to the pool.
        Sql.update(c, """
                UPDATE campaign_recipients SET state = 'pending'
                 WHERE campaign_id = ? AND state = 'claimed' AND block_reason IS NULL
                   AND processed_at < now() - interval '10 minutes'""", id);

        int grant = BATCH;
        if (cap != null) {
            long spent;
            try (var ps = Sql.prepare(c, """
                    SELECT COALESCE(sum(cost_paise), 0) FROM sends
                     WHERE intent_key = ? AND status NOT IN ('blocked', 'deferred')""", CampaignCascades.intentKey(id));
                 var rs = ps.executeQuery()) {
                rs.next();
                spent = rs.getLong(1);
            }
            long unit = channel.equals("whatsapp") ? config.snapshot().longOrZero("rate.whatsapp.marketing_paise") : 0;
            if (spent >= cap || (unit > 0 && cap - spent < unit)) {
                Sql.update(c, """
                        UPDATE campaigns SET status = 'PAUSED', paused_reason = 'budget_cap', updated_at = now()
                         WHERE id = ?""", id);
                LOG.info("campaign {} paused at its budget cap ({} of {} paise)", id, spent, cap);
                return null;
            }
            if (unit > 0) grant = (int) Math.min(grant, (cap - spent) / unit);
        }

        // Token bucket: refill for the time elapsed, take what this batch may use.
        double tokens;
        try (var ps = Sql.prepare(c, """
                UPDATE campaign_rate_buckets
                   SET tokens = LEAST(capacity, tokens + EXTRACT(EPOCH FROM now() - refilled_at) * refill_per_second),
                       refilled_at = now()
                 WHERE campaign_id = ? RETURNING tokens""", id); var rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("campaign " + id + " is RUNNING without a rate bucket");
            tokens = rs.getDouble(1);
        }
        grant = (int) Math.min(grant, Math.floor(tokens));
        if (grant <= 0) return null;

        var claimed = new ArrayList<UUID>();
        try (var ps = Sql.prepare(c, """
                UPDATE campaign_recipients r SET state = 'claimed', attempts = attempts + 1, processed_at = now()
                 WHERE r.campaign_id = ? AND r.identity_id IN (
                       SELECT identity_id FROM campaign_recipients
                        WHERE campaign_id = ? AND state = 'pending' AND bucket = 'treatment'
                        ORDER BY identity_id LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING r.identity_id""", id, id, grant); var rs = ps.executeQuery()) {
            while (rs.next()) claimed.add(rs.getObject(1, UUID.class));
        }
        Sql.update(c, "UPDATE campaign_rate_buckets SET tokens = tokens - ? WHERE campaign_id = ?", claimed.size(), id);

        if (claimed.isEmpty()) {
            finishIfDone(c, id);
            return null;
        }
        return new Batch(claimed, Map.copyOf(vars));
    }

    /**
     * What step 0 of the recipient's run did. Deferred (quiet hours, budget
     * day) stays claimed with the reason: the orchestrator owns the retry.
     */
    private static Void record(Connection c, UUID campaignId, UUID identity, long runId) throws SQLException {
        String result = null, reason = null;
        Long sendId = null;
        try (var ps = Sql.prepare(c, """
                SELECT result, reason, send_id FROM cascade_attempts
                 WHERE run_id = ? AND step_index = 0 ORDER BY id DESC LIMIT 1""", runId); var rs = ps.executeQuery()) {
            if (rs.next()) {
                result = rs.getString(1);
                reason = rs.getString(2);
                sendId = Sql.nullableLong(rs, "send_id");
            }
        }
        String state;
        String note;
        switch (result == null ? "" : result) {
            case "sent", "skipped" -> { state = "sent"; note = null; }
            case "blocked" -> { state = "blocked"; note = reason; }
            case "failed" -> { state = "failed"; note = reason; }
            case "deferred" -> { state = "claimed"; note = "deferred:" + reason; }
            default -> { state = "claimed"; note = "in_cascade"; }   // a live run already held this person
        }
        Sql.update(c, """
                UPDATE campaign_recipients SET state = ?, block_reason = ?, send_id = ?, processed_at = now()
                 WHERE campaign_id = ? AND identity_id = ?""", state, note, sendId, campaignId, identity);
        return null;
    }

    private static void finishIfDone(Connection c, UUID id) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM campaign_recipients
                 WHERE campaign_id = ? AND (state = 'pending' OR (state = 'claimed' AND block_reason IS NULL))""", id);
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) > 0) return;
        }
        Sql.update(c, """
                UPDATE campaigns
                   SET status = 'COMPLETED', finished_at = now(), updated_at = now(),
                       stats = (SELECT COALESCE(jsonb_object_agg(state, n), '{}')
                                  FROM (SELECT state, count(*) AS n FROM campaign_recipients
                                         WHERE campaign_id = ? GROUP BY state) s)
                 WHERE id = ?""", id, id);
        LOG.info("campaign {} completed", id);
    }
}
