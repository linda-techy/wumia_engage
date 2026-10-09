package in.brand.engage.admin.campaigns;

import in.brand.engage.admin.segments.SegmentDsl;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.Decision;
import in.brand.engage.policy.DecisionRequest;
import in.brand.engage.policy.PolicyEngine;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The dry run (phase-6 §5: "the dry run is the product"). Resolves the
 * audience and asks the real {@link PolicyEngine} about each identity with
 * the campaign's template. Sends nothing; its only side effect is the
 * policy engine's own (a holdout assignment row).
 *
 * <p>The result is the breakdown the composer shows: who will receive it,
 * and every reason others will not, campaign exclusions (stale push tokens,
 * unknown WhatsApp capability, the campaign holdout) kept apart from policy
 * blocks, so "no opt-in" is shown plainly rather than hidden.
 *
 * <p>Synchronous and serial: one policy decision per identity. Audiences
 * above {@link #MAX_AUDIENCE} are refused until the estimate moves to a job.
 */
@Singleton
public class DryRun {

    static final int MAX_AUDIENCE = 100_000;
    static final String QUERY_TIMEOUT = "30s";

    public record Estimate(Map<String, Object> breakdown, long costPaise) {}

    private final Db db;
    private final PolicyEngine policy;

    public DryRun(Db db, PolicyEngine policy) {
        this.db = db;
        this.policy = policy;
    }

    public Estimate run(UUID campaignId, SegmentDsl segment, String channel, String templateKey, BigDecimal holdoutPct) {
        var q = CampaignAudience.query(segment, channel, campaignId, holdoutPct);
        record Row(UUID id, boolean held, String excluded) {}
        var rows = db.inTx(c -> {
            Sql.update(c, "SET LOCAL statement_timeout = '" + QUERY_TIMEOUT + "'");
            var out = new ArrayList<Row>();
            try (var ps = Sql.prepare(c, q.sql() + " LIMIT " + (MAX_AUDIENCE + 1), q.params().toArray());
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Row(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3)));
            }
            return out;
        });
        if (rows.size() > MAX_AUDIENCE) {
            throw new Problems.ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "audience-too-large",
                    "the dry run handles up to " + MAX_AUDIENCE + " people; narrow the segment");
        }

        var ch = Channel.fromDb(channel);
        var excluded = new TreeMap<String, Integer>();
        var blocked = new TreeMap<String, Integer>();
        var deferred = new TreeMap<String, Integer>();
        int held = 0, willReceive = 0;
        long cost = 0;
        for (var row : rows) {
            if (row.held()) {
                held++;
            } else if (row.excluded() != null) {
                excluded.merge(row.excluded(), 1, Integer::sum);
            } else {
                switch (policy.decide(DecisionRequest.of(row.id(), ch, templateKey))) {
                    case Decision.Allow a -> {
                        willReceive++;
                        cost += a.unitCostPaise();
                    }
                    case Decision.Block b -> blocked.merge(b.reason().name(), 1, Integer::sum);
                    case Decision.Defer d -> deferred.merge(d.reason().name(), 1, Integer::sum);
                }
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("audience", rows.size());
        out.put("willReceive", willReceive);
        out.put("costPaise", cost);
        out.put("holdout", held);
        out.put("excluded", excluded);
        out.put("blocked", blocked);
        out.put("deferred", deferred);
        out.put("estimatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return new Estimate(out, cost);
    }

    /** Freeze the audience into campaign_recipients: treatment pending, holdout skipped, exclusions left out. */
    public int arm(java.sql.Connection c, UUID campaignId, SegmentDsl segment, String channel, BigDecimal holdoutPct)
            throws java.sql.SQLException {
        var q = CampaignAudience.query(segment, channel, campaignId, holdoutPct);
        var params = new ArrayList<Object>(List.of(campaignId));
        params.addAll(q.params());
        Sql.update(c, "SET LOCAL statement_timeout = '" + QUERY_TIMEOUT + "'");
        return Sql.update(c, """
                INSERT INTO campaign_recipients (campaign_id, identity_id, bucket, state)
                SELECT ?, a.id, CASE WHEN a.held THEN 'holdout' ELSE 'treatment' END,
                              CASE WHEN a.held THEN 'skipped' ELSE 'pending' END
                  FROM (""" + q.sql() + ") a WHERE a.excluded IS NULL", params.toArray());
    }
}
