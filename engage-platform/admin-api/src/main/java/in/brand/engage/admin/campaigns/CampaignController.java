package in.brand.engage.admin.campaigns;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.segments.SegmentDsl;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Campaigns (P6-T06, 05-campaigns.md, phase-6 §4–5).
 *
 * <pre>
 * DRAFT --estimate--> READY | PENDING_APPROVAL --approve--> READY --start--> SCHEDULED | RUNNING
 * RUNNING --> PAUSED (operator, halt, budget cap) --resume--> RUNNING;  any live state --cancel--> CANCELLED
 * RUNNING --(executor, nothing left)--> COMPLETED
 * </pre>
 *
 * <ul>
 * <li>Templates are checked on the server: push needs an active marketing
 *     template; WhatsApp an APPROVED marketing one whose quality is not RED.</li>
 * <li>Approval is required when the estimate exceeds
 *     {@code approval.required_above_paise} or WhatsApp is involved; the
 *     approver is never the author, and approval re-runs the dry run so it is
 *     given on current numbers.</li>
 * <li>Start freezes the audience into {@code campaign_recipients} and records
 *     the config snapshot. Editing a READY campaign sends it back to DRAFT.</li>
 * </ul>
 */
@Controller("/api/campaigns")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CampaignController {

    static final Duration ESTIMATE_FRESH_FOR = Duration.ofHours(24);
    static final Map<String, Integer> DEFAULT_RATE = Map.of("push", 5000, "whatsapp", 600);
    static final Map<String, Integer> MAX_RATE = Map.of("push", 10000, "whatsapp", 1000);
    static final Set<String> CHANNELS = Set.of("push", "whatsapp");
    static final Set<String> EDITABLE = Set.of("DRAFT", "READY", "PENDING_APPROVAL");
    static final Set<String> LIVE = Set.of("SCHEDULED", "RUNNING", "PAUSED");

    @Serdeable
    public record CampaignWrite(@Nullable String name, @Nullable String channel, @Nullable String templateKey,
                                @Nullable String segmentId, @Nullable Map<String, String> vars,
                                @Nullable OffsetDateTime scheduledAt, @Nullable Integer sendRatePerMinute,
                                @Nullable BigDecimal holdoutPct, @Nullable Long budgetCapPaise,
                                @Nullable Integer ttlSeconds, @Nullable String followUpChannel,
                                @Nullable String followUpTemplateKey, @Nullable Integer followUpAfterMinutes) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final DryRun dryRun;
    private final ConfigResolver config;

    public CampaignController(Db db, CurrentOperator current, AuditLog audit, ObjectMapper json, DryRun dryRun,
                              ConfigResolver config) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.json = json;
        this.dryRun = dryRun;
        this.config = config;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public List<Map<String, Object>> list() {
        return db.inTx(c -> Rows.list(c, this::parse, SELECT + " ORDER BY k.created_at DESC LIMIT 200"));
    }

    @RequiresRole(Role.VIEWER)
    @Get("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return db.inTx(c -> detail(c, uuid(id)));
    }

    /** The composer's template picker: only what a campaign on this channel may send. */
    @RequiresRole(Role.VIEWER)
    @Get("/templates")
    public List<Map<String, Object>> templates(@QueryValue String channel) {
        if (!CHANNELS.contains(channel)) throw Problems.badRequest("channel is push or whatsapp");
        return db.inTx(c -> Rows.list(c, channel.equals("push") ? """
                SELECT t.key, t.channel::text AS channel FROM templates t
                 WHERE t.channel = 'push' AND t.category = 'marketing' AND t.status = 'active' ORDER BY t.key"""
                : """
                SELECT DISTINCT t.key, t.channel::text AS channel FROM templates t JOIN wa_templates w ON w.key = t.key
                 WHERE t.channel = 'whatsapp' AND t.category = 'marketing' AND t.status = 'active'
                   AND w.status = 'APPROVED' AND w.approved_category = 'marketing' AND w.quality <> 'RED'
                 ORDER BY t.key"""));
    }

    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post
    public HttpResponse<Map<String, Object>> create(@Body CampaignWrite body) {
        var actor = current.id();
        Map<String, Object> created = db.inTx(c -> {
            var w = validate(c, body);
            UUID id;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO campaigns (name, channel, template_key, segment_id, vars, scheduled_at, send_rate_per_minute,
                                           holdout_pct, budget_cap_paise, ttl_seconds, follow_up_channel,
                                           follow_up_template_key, follow_up_after_minutes, created_by)
                    VALUES (?, CAST(? AS channel), ?, ?, ?::jsonb, ?, ?, CAST(? AS numeric), ?, ?, CAST(? AS channel), ?, ?, ?)
                    RETURNING id""", w.params(actor));
                 var rs = ps.executeQuery()) {
                rs.next();
                id = rs.getObject(1, UUID.class);
            }
            audit.record(c, actor, "campaign.create", "campaign", id.toString(), null, toJson(w.summary()));
            return detail(c, id);
        });
        return HttpResponse.created(created);
    }

    /** Replaces the editable fields. Clears the estimate and any approval: they were for the old campaign. */
    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Patch("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Body CampaignWrite body) {
        var campaignId = uuid(id);
        var actor = current.id();
        return db.inTx(c -> {
            var before = lock(c, campaignId);
            requireStatus(before, EDITABLE, "only a DRAFT, READY or PENDING_APPROVAL campaign can be edited");
            var w = validate(c, body);
            var params = new java.util.ArrayList<>(java.util.Arrays.asList(w.params(actor)));   // nulls allowed
            params.removeLast();                                            // created_by does not change
            params.add(campaignId);
            Sql.update(c, """
                    UPDATE campaigns SET name = ?, channel = CAST(? AS channel), template_key = ?, segment_id = ?,
                           vars = ?::jsonb, scheduled_at = ?, send_rate_per_minute = ?, holdout_pct = CAST(? AS numeric),
                           budget_cap_paise = ?, ttl_seconds = ?, follow_up_channel = CAST(? AS channel),
                           follow_up_template_key = ?, follow_up_after_minutes = ?,
                           status = 'DRAFT', estimate = NULL, estimated_at = NULL, estimated_cost_paise = NULL,
                           approved_by = NULL, approved_at = NULL, updated_at = now()
                     WHERE id = ?""", params.toArray());
            audit.record(c, actor, "campaign.update", "campaign", campaignId.toString(), null, toJson(w.summary()));
            return detail(c, campaignId);
        });
    }

    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post("/{id}/estimate")
    public Map<String, Object> estimate(@PathVariable String id) {
        var campaignId = uuid(id);
        var actor = current.id();
        var k = db.inTx(c -> lock(c, campaignId));
        requireStatus(k, EDITABLE, "only a DRAFT, READY or PENDING_APPROVAL campaign can be estimated");
        var est = runDryRun(campaignId, k);
        return db.inTx(c -> {
            var now = lock(c, campaignId);
            requireStatus(now, EDITABLE, "the campaign changed while it was being estimated");
            var status = approvalRequired(now, est.costPaise()) ? "PENDING_APPROVAL" : "READY";
            Sql.update(c, """
                    UPDATE campaigns SET estimate = ?::jsonb, estimated_at = now(), estimated_cost_paise = ?, status = ?,
                                         approved_by = NULL, approved_at = NULL, updated_at = now()
                     WHERE id = ?""", toJson(est.breakdown()), est.costPaise(), status, campaignId);
            audit.record(c, actor, "campaign.estimate", "campaign", campaignId.toString(), null, toJson(est.breakdown()));
            return detail(c, campaignId);
        });
    }

    /** Four eyes. Re-runs the dry run, so the approval is given on current numbers. */
    @RequiresRole(Role.CAMPAIGN_SEND)
    @Post("/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id) {
        var campaignId = uuid(id);
        var actor = current.id();
        var k = db.inTx(c -> lock(c, campaignId));
        requireStatus(k, Set.of("PENDING_APPROVAL"), "only a campaign waiting for approval can be approved");
        if (actor.equals(k.get("createdById"))) {
            throw Problems.conflict("four-eyes", "a campaign is approved by someone other than its author");
        }
        var est = runDryRun(campaignId, k);
        return db.inTx(c -> {
            requireStatus(lock(c, campaignId), Set.of("PENDING_APPROVAL"), "the campaign changed while it was being approved");
            Sql.update(c, """
                    UPDATE campaigns SET estimate = ?::jsonb, estimated_at = now(), estimated_cost_paise = ?,
                                         approved_by = ?, approved_at = now(), status = 'READY', updated_at = now()
                     WHERE id = ?""", toJson(est.breakdown()), est.costPaise(), actor, campaignId);
            audit.record(c, actor, "campaign.approve", "campaign", campaignId.toString(), null,
                    toJson(Map.of("costPaise", est.costPaise(), "willReceive", est.breakdown().get("willReceive"))));
            return detail(c, campaignId);
        });
    }

    /** Arms: freezes the audience, snapshots config, creates the rate bucket. */
    @RequiresRole(Role.CAMPAIGN_SEND)
    @Post("/{id}/start")
    public Map<String, Object> start(@PathVariable String id) {
        var campaignId = uuid(id);
        var actor = current.id();
        var snapshotId = config.snapshot().id();
        return db.inTx(c -> {
            var k = lock(c, campaignId);
            requireStatus(k, Set.of("READY"), "only a READY campaign can be started; estimate it first");
            var estimatedAt = (OffsetDateTime) k.get("estimatedAt");
            if (estimatedAt == null || estimatedAt.isBefore(OffsetDateTime.now().minus(ESTIMATE_FRESH_FOR))) {
                throw Problems.conflict("estimate-stale", "the estimate is over 24 hours old; estimate again");
            }
            var cost = (Long) k.get("estimatedCostPaise");
            if (approvalRequired(k, cost == null ? 0 : cost) && k.get("approvedById") == null) {
                throw Problems.conflict("approval-required", "this campaign needs a second operator's approval");
            }
            if (halted(c, (String) k.get("channel"))) {
                throw Problems.conflict("halted", "marketing or this channel is halted; release the halt first");
            }
            var segment = segment(c, (UUID) k.get("segmentId"));
            int recipients = dryRun.arm(c, campaignId, segment, (String) k.get("channel"), (BigDecimal) k.get("holdoutPct"));
            int rate = (Integer) k.get("sendRatePerMinute");
            int capacity = Math.min(rate, BATCH);
            Sql.update(c, """
                    INSERT INTO campaign_rate_buckets (campaign_id, capacity, refill_per_second, tokens)
                    VALUES (?, ?, CAST(? AS numeric), ?)
                    ON CONFLICT (campaign_id) DO UPDATE SET capacity = EXCLUDED.capacity,
                           refill_per_second = EXCLUDED.refill_per_second, tokens = EXCLUDED.tokens, refilled_at = now()""",
                    campaignId, capacity, String.valueOf(rate / 60.0), capacity);
            var scheduled = (OffsetDateTime) k.get("scheduledAt");
            var later = scheduled != null && scheduled.isAfter(OffsetDateTime.now());
            Sql.update(c, """
                    UPDATE campaigns SET status = ?, config_snapshot_id = ?, paused_reason = NULL,
                                         started_at = CASE WHEN ? THEN NULL ELSE now() END, updated_at = now()
                     WHERE id = ?""", later ? "SCHEDULED" : "RUNNING", snapshotId, later, campaignId);
            audit.record(c, actor, "campaign.start", "campaign", campaignId.toString(), null,
                    toJson(Map.of("recipients", recipients, "configSnapshotId", snapshotId)));
            return detail(c, campaignId);
        });
    }

    @RequiresRole(Role.CAMPAIGN_SEND)
    @Post("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        return transition(uuid(id), Set.of("SCHEDULED", "RUNNING"), "campaign.pause", """
                UPDATE campaigns SET status = 'PAUSED', paused_reason = 'operator', updated_at = now() WHERE id = ?""");
    }

    @RequiresRole(Role.CAMPAIGN_SEND)
    @Post("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        var campaignId = uuid(id);
        var actor = current.id();
        return db.inTx(c -> {
            var k = lock(c, campaignId);
            requireStatus(k, Set.of("PAUSED"), "only a PAUSED campaign can be resumed");
            if (halted(c, (String) k.get("channel"))) {
                throw Problems.conflict("halted", "marketing or this channel is halted; release the halt first");
            }
            if ("budget_cap".equals(k.get("pausedReason"))) {
                throw Problems.conflict("budget-cap", "it stopped at its budget cap; raise the cap with a new campaign");
            }
            Sql.update(c, """
                    UPDATE campaigns SET status = CASE WHEN started_at IS NULL THEN 'SCHEDULED' ELSE 'RUNNING' END,
                                         paused_reason = NULL, updated_at = now() WHERE id = ?""", campaignId);
            audit.record(c, actor, "campaign.resume", "campaign", campaignId.toString(), null, null);
            return detail(c, campaignId);
        });
    }

    /** Terminal. Recipients not yet reached are skipped; sends already made stand. */
    @RequiresRole(Role.CAMPAIGN_SEND)
    @Post("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        var campaignId = uuid(id);
        var actor = current.id();
        return db.inTx(c -> {
            var k = lock(c, campaignId);
            if (Set.of("COMPLETED", "CANCELLED", "FAILED").contains((String) k.get("status"))) {
                throw Problems.conflict("campaign-finished", "this campaign is already " + k.get("status"));
            }
            Sql.update(c, """
                    UPDATE campaign_recipients SET state = 'skipped', block_reason = 'cancelled', processed_at = now()
                     WHERE campaign_id = ? AND state = 'pending'""", campaignId);
            Sql.update(c, """
                    UPDATE campaigns SET status = 'CANCELLED', finished_at = now(), updated_at = now() WHERE id = ?""",
                    campaignId);
            audit.record(c, actor, "campaign.cancel", "campaign", campaignId.toString(),
                    toJson(Map.of("status", k.get("status"))), null);
            return detail(c, campaignId);
        });
    }

    /* ------------------------------------------------------------------ */

    static final int BATCH = 100;                      // the worker executor's batch; the bucket holds one

    private static final String SELECT = """
            SELECT k.id, k.name, k.channel::text AS channel, k.template_key, k.segment_id, s.name AS segment_name,
                   k.vars, k.status, k.paused_reason, k.scheduled_at, k.send_rate_per_minute, k.holdout_pct,
                   k.budget_cap_paise, k.ttl_seconds, k.follow_up_channel::text AS follow_up_channel,
                   k.follow_up_template_key, k.follow_up_after_minutes, k.estimate, k.estimated_at,
                   k.estimated_cost_paise, cb.email AS created_by, ab.email AS approved_by, k.approved_at,
                   k.config_snapshot_id, k.started_at, k.finished_at, k.stats, k.created_at, k.updated_at
              FROM campaigns k
              LEFT JOIN segments s ON s.id = k.segment_id
              LEFT JOIN operators cb ON cb.id = k.created_by
              LEFT JOIN operators ab ON ab.id = k.approved_by""";

    private Map<String, Object> detail(Connection c, UUID id) throws SQLException {
        var rows = Rows.list(c, this::parse, SELECT + " WHERE k.id = ?", id);
        if (rows.isEmpty()) throw Problems.notFound("no such campaign");
        var out = rows.getFirst();
        var recipients = new LinkedHashMap<String, Object>();
        for (var r : Rows.list(c, """
                SELECT state, count(*) AS n FROM campaign_recipients WHERE campaign_id = ? GROUP BY state ORDER BY state""", id)) {
            recipients.put((String) r.get("state"), r.get("n"));
        }
        out.put("recipients", recipients);
        var cost = (Number) out.get("estimatedCostPaise");
        out.put("approvalRequired", approvalRequired(out, cost == null ? 0 : cost.longValue()));
        return out;
    }

    /** The campaign row, locked, with the fields the lifecycle checks. */
    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT status, channel::text AS channel, follow_up_channel::text AS follow_up_channel, template_key,
                       segment_id, holdout_pct, send_rate_per_minute, scheduled_at, estimated_at, estimated_cost_paise,
                       created_by, approved_by, paused_reason
                  FROM campaigns WHERE id = ? FOR UPDATE""", id); var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.notFound("no such campaign");
            var m = new LinkedHashMap<String, Object>();
            m.put("status", rs.getString("status"));
            m.put("channel", rs.getString("channel"));
            m.put("followUpChannel", rs.getString("follow_up_channel"));
            m.put("templateKey", rs.getString("template_key"));
            m.put("segmentId", rs.getObject("segment_id", UUID.class));
            m.put("holdoutPct", rs.getBigDecimal("holdout_pct"));
            m.put("sendRatePerMinute", rs.getInt("send_rate_per_minute"));
            m.put("scheduledAt", Sql.timestamp(rs, "scheduled_at"));
            m.put("estimatedAt", Sql.timestamp(rs, "estimated_at"));
            m.put("estimatedCostPaise", Sql.nullableLong(rs, "estimated_cost_paise"));
            m.put("createdById", rs.getObject("created_by", UUID.class));
            m.put("approvedById", rs.getObject("approved_by", UUID.class));
            m.put("pausedReason", rs.getString("paused_reason"));
            return m;
        }
    }

    private DryRun.Estimate runDryRun(UUID campaignId, Map<String, Object> k) {
        var segment = db.inTx(c -> segment(c, (UUID) k.get("segmentId")));
        return dryRun.run(campaignId, segment, (String) k.get("channel"), (String) k.get("templateKey"),
                (BigDecimal) k.get("holdoutPct"));
    }

    private SegmentDsl segment(Connection c, UUID segmentId) throws SQLException {
        if (segmentId == null) throw Problems.conflict("no-segment", "choose a segment first");
        try (var ps = Sql.prepare(c, "SELECT definition::text FROM segments WHERE id = ?", segmentId);
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.conflict("no-segment", "the campaign's segment no longer exists");
            return SegmentDsl.parse(parse(rs.getString(1)));
        }
    }

    /** WhatsApp in either step, or an estimate above the threshold. */
    private boolean approvalRequired(Map<String, Object> k, long costPaise) {
        if ("whatsapp".equals(k.get("channel")) || "whatsapp".equals(k.get("followUpChannel"))) return true;
        return costPaise > config.snapshot().longOrZero("approval.required_above_paise");
    }

    private static boolean halted(Connection c, String channel) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM config_current
                 WHERE value = 'true'::jsonb
                   AND ((key = 'halt.marketing' AND selector = '*')
                     OR (key = 'halt.channel' AND selector IN (?, '*')))""", channel); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private Map<String, Object> transition(UUID id, Set<String> from, String action, String sql) {
        var actor = current.id();
        return db.inTx(c -> {
            var k = lock(c, id);
            requireStatus(k, from, "not allowed from " + k.get("status"));
            Sql.update(c, sql, id);
            audit.record(c, actor, action, "campaign", id.toString(), toJson(Map.of("status", k.get("status"))), null);
            return detail(c, id);
        });
    }

    private static void requireStatus(Map<String, Object> k, Set<String> allowed, String message) {
        if (!allowed.contains((String) k.get("status"))) {
            throw Problems.conflict("wrong-status", message + " (it is " + k.get("status") + ")");
        }
    }

    /* ------------------------------ validation ------------------------------ */

    private record Valid(String name, String channel, String templateKey, UUID segmentId, String varsJson,
                         OffsetDateTime scheduledAt, int rate, BigDecimal holdoutPct, Long budgetCapPaise,
                         Integer ttlSeconds, String followUpChannel, String followUpTemplateKey,
                         Integer followUpAfterMinutes) {

        Object[] params(UUID actor) {
            return new Object[] {name, channel, templateKey, segmentId, varsJson, scheduledAt, rate,
                    holdoutPct.toPlainString(), budgetCapPaise, ttlSeconds, followUpChannel, followUpTemplateKey,
                    followUpAfterMinutes, actor};
        }

        Map<String, Object> summary() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("channel", channel);
            m.put("templateKey", templateKey);
            m.put("segmentId", segmentId.toString());
            m.put("rate", rate);
            m.put("holdoutPct", holdoutPct.toPlainString());
            if (budgetCapPaise != null) m.put("budgetCapPaise", budgetCapPaise);
            if (followUpChannel != null) m.put("followUp", followUpChannel + ":" + followUpTemplateKey);
            return m;
        }
    }

    private Valid validate(Connection c, CampaignWrite b) throws SQLException {
        if (b == null) throw Problems.badRequest("name, channel, templateKey and segmentId are required");
        var name = b.name() == null ? "" : b.name().strip();
        if (name.isEmpty() || name.length() > 120) throw Problems.badRequest("a name of 1 to 120 characters is required");
        var channel = b.channel();
        if (channel == null || !CHANNELS.contains(channel)) {
            throw Problems.badRequest("campaigns run on push or whatsapp (email arrives in phase 7)");
        }
        eligible(c, channel, b.templateKey());
        var segmentId = segmentId(c, b.segmentId());

        var vars = b.vars() == null ? Map.<String, String>of() : b.vars();
        if (vars.size() > 20) throw Problems.badRequest("at most 20 template variables");
        for (var e : vars.entrySet()) {
            if (e.getKey() == null || !e.getKey().matches("[a-z][a-z0-9_]{0,39}")) {
                throw Problems.badRequest("variable names are lowercase identifiers");
            }
            if (e.getValue() == null || e.getValue().length() > 300) throw Problems.badRequest("variable values are 1 to 300 characters");
        }
        if (vars.containsKey("url") && !vars.get("url").startsWith("https://")) throw Problems.badRequest("url must be https://");

        var scheduledAt = b.scheduledAt();
        if (scheduledAt != null && scheduledAt.isAfter(OffsetDateTime.now().plusDays(90))) {
            throw Problems.badRequest("schedule at most 90 days ahead");
        }
        int rate = b.sendRatePerMinute() == null ? DEFAULT_RATE.get(channel) : b.sendRatePerMinute();
        if (rate < 1 || rate > MAX_RATE.get(channel)) {
            throw Problems.badRequest(channel + " sends at 1 to " + MAX_RATE.get(channel) + " per minute");
        }
        var holdout = b.holdoutPct() == null ? BigDecimal.ZERO : b.holdoutPct();
        if (holdout.signum() < 0 || holdout.compareTo(BigDecimal.valueOf(50)) > 0 || holdout.scale() > 2) {
            throw Problems.badRequest("holdoutPct is 0 to 50, two decimals at most");
        }
        var cap = b.budgetCapPaise();
        if (cap != null && cap < 0) throw Problems.badRequest("budgetCapPaise cannot be negative");
        var ttl = b.ttlSeconds();
        if (ttl != null && (!channel.equals("push") || ttl < 60 || ttl > 172_800)) {
            throw Problems.badRequest("ttlSeconds is for push: 60 to 172800 (48 h)");
        }

        var fuChannel = b.followUpChannel();
        var fuTemplate = b.followUpTemplateKey();
        var fuAfter = b.followUpAfterMinutes();
        if (fuChannel != null || fuTemplate != null || fuAfter != null) {
            if (fuChannel == null || fuTemplate == null || fuAfter == null) {
                throw Problems.badRequest("a follow-up needs followUpChannel, followUpTemplateKey and followUpAfterMinutes");
            }
            if (!CHANNELS.contains(fuChannel) || fuChannel.equals(channel)) {
                throw Problems.badRequest("the follow-up runs on the other channel");
            }
            if (fuAfter < 60 || fuAfter > 4320) throw Problems.badRequest("followUpAfterMinutes is 60 to 4320 (3 days)");
            eligible(c, fuChannel, fuTemplate);
        }
        if ((channel.equals("whatsapp") || "whatsapp".equals(fuChannel)) && (cap == null || cap == 0)) {
            throw Problems.badRequest("a WhatsApp campaign needs a budget cap: every send is paid");
        }
        return new Valid(name, channel, b.templateKey(), segmentId, toJson(vars), scheduledAt, rate, holdout, cap, ttl,
                fuChannel, fuTemplate, fuAfter);
    }

    /** The same rule as the picker, enforced on write. */
    private static void eligible(Connection c, String channel, String templateKey) throws SQLException {
        if (templateKey == null) throw Problems.badRequest("templateKey is required");
        var sql = channel.equals("push") ? """
                SELECT 1 FROM templates WHERE key = ? AND channel = 'push' AND category = 'marketing' AND status = 'active'"""
                : """
                SELECT 1 FROM templates t JOIN wa_templates w ON w.key = t.key
                 WHERE t.key = ? AND t.channel = 'whatsapp' AND t.category = 'marketing' AND t.status = 'active'
                   AND w.status = 'APPROVED' AND w.approved_category = 'marketing' AND w.quality <> 'RED'""";
        try (var ps = Sql.prepare(c, sql, templateKey); var rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw Problems.badRequest(channel.equals("push")
                        ? templateKey + " is not an active push marketing template"
                        : templateKey + " is not an APPROVED WhatsApp marketing template with quality above RED");
            }
        }
    }

    private static UUID segmentId(Connection c, String raw) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw Problems.badRequest("segmentId is required");
        }
        try (var ps = Sql.prepare(c, "SELECT 1 FROM segments WHERE id = ?", id); var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.badRequest("no such segment");
        }
        return id;
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such campaign");
        }
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Argument.OBJECT_ARGUMENT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
