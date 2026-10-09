package in.brand.engage.admin.config;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The config API (P6-T03, 02-config-and-settings.md).
 *
 * <ul>
 * <li>SAFE and GUARDED keys: a write inserts a {@code config_versions} row at
 *     once. CRITICAL keys: a write is a {@code config_proposals} row, and only
 *     a different operator's approval inserts the version (four eyes; the
 *     table CHECK enforces it too, this returns the clear 409 first).</li>
 * <li>Every write needs CONFIG_ADMIN and the key's {@code min_role}, a reason
 *     of 10+ characters, and a value valid for the key ({@link ConfigValues}).</li>
 * <li>Effective dating: {@code effectiveFrom} may be in the future (a festive
 *     cap staged in advance), never in the past; {@code effectiveTo} makes the
 *     value revert on its own. Policy picks a start time up within its 30 s
 *     cache expiry, with no insert needed.</li>
 * <li>Kill switches are not written here: {@code /api/halt} also pauses
 *     campaigns and needs no approval.</li>
 * </ul>
 */
@Controller("/api/config")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ConfigController {

    static final int MIN_REASON = 10;
    static final int MAX_REASON = 500;
    /** Clock skew between the console and the server, not a licence to backdate. */
    static final Duration BACKDATE_SLACK = Duration.ofMinutes(1);

    @Serdeable
    public record ConfigWrite(@Nullable String selector, @Nullable Object value, @Nullable OffsetDateTime effectiveFrom,
                              @Nullable OffsetDateTime effectiveTo, @Nullable String reason) {}

    @Serdeable
    public record Rejection(@Nullable String reason) {}

    record Key(String key, String scope, String valueType, Map<String, Object> schema, String risk, String minRole) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final ObjectMapper json;

    public ConfigController(Db db, CurrentOperator current, AuditLog audit, ObjectMapper json) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.json = json;
    }

    /** Every key with its definition, the versions in force and scheduled, and pending proposals. */
    @RequiresRole(Role.VIEWER)
    @Get
    public List<Map<String, Object>> list() {
        return db.inTx(c -> {
            var inForce = grouped(c, VERSION_SELECT + " FROM config_current cc JOIN config_versions v ON v.id = cc.id"
                    + VERSION_JOINS
                    + " ORDER BY v.key, v.selector");
            var scheduled = grouped(c, VERSION_SELECT + " FROM config_versions v" + VERSION_JOINS
                    + " WHERE v.effective_from > now() ORDER BY v.key, v.effective_from");
            var pending = new HashMap<String, List<Map<String, Object>>>();
            try (var ps = Sql.prepare(c, PROPOSAL_SELECT + " WHERE p.status = 'PENDING' ORDER BY p.created_at");
                 var rs = ps.executeQuery()) {
                while (rs.next()) pending.computeIfAbsent(rs.getString("key"), k -> new ArrayList<>()).add(proposal(rs));
            }

            var out = new ArrayList<Map<String, Object>>();
            try (var ps = Sql.prepare(c, """
                    SELECT key, scope, value_type, json_schema::text AS json_schema, label, help_text, risk, min_role,
                           default_value::text AS default_value
                      FROM config_keys WHERE NOT deprecated ORDER BY sort_order, key""");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var key = rs.getString("key");
                    var row = new LinkedHashMap<String, Object>();
                    row.put("key", key);
                    row.put("scope", rs.getString("scope"));
                    row.put("valueType", rs.getString("value_type"));
                    row.put("jsonSchema", parse(rs.getString("json_schema")));
                    row.put("label", rs.getString("label"));
                    row.put("helpText", rs.getString("help_text"));
                    row.put("risk", rs.getString("risk"));
                    row.put("minRole", rs.getString("min_role"));
                    row.put("defaultValue", parse(rs.getString("default_value")));
                    row.put("inForce", inForce.getOrDefault(key, List.of()));
                    row.put("scheduled", scheduled.getOrDefault(key, List.of()));
                    row.put("pending", pending.getOrDefault(key, List.of()));
                    out.add(row);
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.ANALYST)
    @Get("/{key}/history")
    public List<Map<String, Object>> history(@PathVariable String key) {
        return db.inTx(c -> {
            key(c, key);
            var out = new ArrayList<Map<String, Object>>();
            try (var ps = Sql.prepare(c, VERSION_SELECT + " FROM config_versions v" + VERSION_JOINS
                    + " WHERE v.key = ? ORDER BY v.created_at DESC, v.id DESC", key);
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(version(rs));
            }
            return out;
        });
    }

    @RequiresRole(Role.CONFIG_ADMIN)
    @Post("/{key}")
    public HttpResponse<Map<String, Object>> write(@PathVariable String key, @Body ConfigWrite body) {
        if (body == null) throw Problems.badRequest("selector, value and reason are required");
        var actor = current.id();
        return db.inTx(c -> {
            var k = key(c, key);
            if (k.key().startsWith("halt.")) {
                throw Problems.conflict("use-halt", "kill switches are set and released through /api/halt");
            }
            current.require(k.minRole());
            var selector = ConfigValues.selector(k.scope(), body.selector());
            ConfigValues.validate(k.valueType(), k.schema(), body.value());
            var reason = reason(body.reason());
            window(body.effectiveFrom(), body.effectiveTo());
            var value = toJson(body.value());

            var after = new LinkedHashMap<String, Object>();
            after.put("selector", selector);
            after.put("value", body.value());
            if (body.effectiveFrom() != null) after.put("effectiveFrom", body.effectiveFrom().toString());
            if (body.effectiveTo() != null) after.put("effectiveTo", body.effectiveTo().toString());
            after.put("reason", reason);

            if (k.risk().equals("CRITICAL")) {
                lockKey(c, k.key(), selector);
                try (var ps = Sql.prepare(c, """
                        SELECT id FROM config_proposals WHERE key = ? AND selector = ? AND status = 'PENDING'""",
                        k.key(), selector); var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw Problems.conflict("proposal-pending",
                                "proposal " + rs.getLong(1) + " for this setting is waiting for approval; withdraw it first");
                    }
                }
                long id;
                try (var ps = Sql.prepare(c, """
                        INSERT INTO config_proposals (key, selector, value, effective_from, effective_to, reason, proposed_by)
                        VALUES (?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id""",
                        k.key(), selector, value, body.effectiveFrom(), body.effectiveTo(), reason, actor);
                     var rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
                after.put("proposalId", id);
                audit.record(c, actor, "config.propose", "config", k.key() + ":" + selector, null, toJson(after));
                return HttpResponse.<Map<String, Object>>status(HttpStatus.ACCEPTED)
                        .body(Map.of("proposalId", id, "status", "PENDING", "key", k.key(), "selector", selector));
            }

            var before = exact(c, k.key(), selector);
            long id = insertVersion(c, k.key(), selector, value, body.effectiveFrom(), body.effectiveTo(), actor, null, reason);
            after.put("versionId", id);
            audit.record(c, actor, "config.update", "config", k.key() + ":" + selector,
                    before == null ? null : toJson(Map.of("value", before)), toJson(after));
            return HttpResponse.created(Map.<String, Object>of("versionId", id, "key", k.key(), "selector", selector));
        });
    }

    /** A second CONFIG_ADMIN approves: the version is inserted now, with approved_by. */
    @RequiresRole(Role.CONFIG_ADMIN)
    @Post("/proposals/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id) {
        var actor = current.id();
        return db.inTx(c -> {
            var p = pendingProposal(c, id);
            var k = key(c, (String) p.get("key"));
            current.require(k.minRole());
            if (actor.equals(p.get("proposedBy"))) {
                throw Problems.conflict("four-eyes", "a proposal is approved by someone other than its proposer");
            }
            var from = (OffsetDateTime) p.get("effectiveFrom");
            var to = (OffsetDateTime) p.get("effectiveTo");
            if (to != null && !to.isAfter(OffsetDateTime.now())) {
                throw Problems.conflict("window-passed", "this proposal's window has already ended; reject it");
            }
            var value = parse((String) p.get("valueJson"));
            ConfigValues.validate(k.valueType(), k.schema(), value);       // the schema may have tightened since
            // A start that has passed while it waited takes effect on approval, never backdated.
            if (from != null && from.isBefore(OffsetDateTime.now())) from = null;
            var selector = (String) p.get("selector");
            long versionId = insertVersion(c, k.key(), selector, (String) p.get("valueJson"), from, to,
                    (UUID) p.get("proposedBy"), actor, (String) p.get("reason"));
            Sql.update(c, """
                    UPDATE config_proposals
                       SET status = 'APPROVED', decided_by = ?, decided_at = now(), applied_version_id = ?
                     WHERE id = ?""", actor, versionId, id);
            audit.record(c, actor, "config.approve", "config_proposal", Long.toString(id), null,
                    toJson(Map.of("key", k.key(), "selector", selector, "versionId", versionId)));
            return Map.<String, Object>of("proposalId", id, "status", "APPROVED", "versionId", versionId);
        });
    }

    @RequiresRole(Role.CONFIG_ADMIN)
    @Post("/proposals/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @Nullable @Body Rejection body) {
        var actor = current.id();
        return db.inTx(c -> {
            var p = pendingProposal(c, id);
            current.require(key(c, (String) p.get("key")).minRole());
            if (actor.equals(p.get("proposedBy"))) {
                throw Problems.conflict("four-eyes", "withdraw your own proposal instead of rejecting it");
            }
            Sql.update(c, """
                    UPDATE config_proposals SET status = 'REJECTED', decided_by = ?, decided_at = now() WHERE id = ?""",
                    actor, id);
            var after = new LinkedHashMap<String, Object>();
            if (body != null && body.reason() != null) after.put("reason", body.reason().strip());
            audit.record(c, actor, "config.reject", "config_proposal", Long.toString(id), null, toJson(after));
            return Map.<String, Object>of("proposalId", id, "status", "REJECTED");
        });
    }

    /** The proposer withdraws. decided_by stays NULL: the four-eyes CHECK forbids the proposer there. */
    @RequiresRole(Role.CONFIG_ADMIN)
    @Delete("/proposals/{id}")
    public Map<String, Object> withdraw(@PathVariable long id) {
        var actor = current.id();
        return db.inTx(c -> {
            var p = pendingProposal(c, id);
            if (!actor.equals(p.get("proposedBy"))) throw Problems.forbidden("only the proposer can withdraw a proposal");
            Sql.update(c, "UPDATE config_proposals SET status = 'WITHDRAWN', decided_at = now() WHERE id = ?", id);
            audit.record(c, actor, "config.withdraw", "config_proposal", Long.toString(id), null, null);
            return Map.<String, Object>of("proposalId", id, "status", "WITHDRAWN");
        });
    }

    /** The whole resolved config a send was decided under (sends.config_snapshot_id). */
    @RequiresRole(Role.VIEWER)
    @Get("/snapshots/{id}")
    public Map<String, Object> snapshot(@PathVariable long id) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT id, created_at, version_ids, resolved::text AS resolved FROM config_snapshots WHERE id = ?""", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no such snapshot");
                var out = new LinkedHashMap<String, Object>();
                out.put("id", rs.getLong("id"));
                out.put("createdAt", Sql.timestamp(rs, "created_at"));
                out.put("versionIds", Arrays.asList((Long[]) rs.getArray("version_ids").getArray()));
                out.put("resolved", parse(rs.getString("resolved")));
                return out;
            }
        });
    }

    /* ------------------------------------------------------------------ */

    private static final String VERSION_SELECT = """
            SELECT v.id, v.key, v.selector, v.value::text AS value, v.effective_from, v.effective_to,
                   ch.email AS changed_by, ap.email AS approved_by, v.reason, v.created_at""";
    private static final String VERSION_JOINS = """

              LEFT JOIN operators ch ON ch.id = v.changed_by
              LEFT JOIN operators ap ON ap.id = v.approved_by""";
    private static final String PROPOSAL_SELECT = """
            SELECT p.id, p.key, p.selector, p.value::text AS value, p.effective_from, p.effective_to, p.reason,
                   o.email AS proposed_by, p.created_at
              FROM config_proposals p JOIN operators o ON o.id = p.proposed_by""";

    private Map<String, List<Map<String, Object>>> grouped(Connection c, String sql) throws SQLException {
        var out = new HashMap<String, List<Map<String, Object>>>();
        try (var ps = Sql.prepare(c, sql); var rs = ps.executeQuery()) {
            while (rs.next()) out.computeIfAbsent(rs.getString("key"), k -> new ArrayList<>()).add(version(rs));
        }
        return out;
    }

    private Map<String, Object> version(ResultSet rs) throws SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("versionId", rs.getLong("id"));
        row.put("selector", rs.getString("selector"));
        row.put("value", parse(rs.getString("value")));
        row.put("effectiveFrom", Sql.timestamp(rs, "effective_from"));
        row.put("effectiveTo", Sql.timestamp(rs, "effective_to"));
        row.put("changedBy", rs.getString("changed_by"));
        row.put("approvedBy", rs.getString("approved_by"));
        row.put("reason", rs.getString("reason"));
        row.put("createdAt", Sql.timestamp(rs, "created_at"));
        return row;
    }

    private Map<String, Object> proposal(ResultSet rs) throws SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("proposalId", rs.getLong("id"));
        row.put("selector", rs.getString("selector"));
        row.put("value", parse(rs.getString("value")));
        row.put("effectiveFrom", Sql.timestamp(rs, "effective_from"));
        row.put("effectiveTo", Sql.timestamp(rs, "effective_to"));
        row.put("reason", rs.getString("reason"));
        row.put("proposedBy", rs.getString("proposed_by"));
        row.put("createdAt", Sql.timestamp(rs, "created_at"));
        return row;
    }

    private Key key(Connection c, String key) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT key, scope, value_type, json_schema::text AS json_schema, risk, min_role
                  FROM config_keys WHERE key = ? AND NOT deprecated""", key);
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.notFound("no such setting " + key);
            @SuppressWarnings("unchecked")
            var schema = (Map<String, Object>) parse(rs.getString("json_schema"));
            return new Key(rs.getString("key"), rs.getString("scope"), rs.getString("value_type"), schema,
                    rs.getString("risk"), rs.getString("min_role"));
        }
    }

    /** The proposal, locked; 404 if absent, 409 if already decided. */
    private static Map<String, Object> pendingProposal(Connection c, long id) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT key, selector, value::text AS value, effective_from, effective_to, reason, proposed_by, status
                  FROM config_proposals WHERE id = ? FOR UPDATE""", id);
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.notFound("no such proposal");
            if (!"PENDING".equals(rs.getString("status"))) {
                throw Problems.conflict("proposal-decided", "this proposal is already " + rs.getString("status"));
            }
            var p = new HashMap<String, Object>();
            p.put("key", rs.getString("key"));
            p.put("selector", rs.getString("selector"));
            p.put("valueJson", rs.getString("value"));
            p.put("effectiveFrom", Sql.timestamp(rs, "effective_from"));
            p.put("effectiveTo", Sql.timestamp(rs, "effective_to"));
            p.put("reason", rs.getString("reason"));
            p.put("proposedBy", rs.getObject("proposed_by", UUID.class));
            return p;
        }
    }

    private static long insertVersion(Connection c, String key, String selector, String valueJson,
                                      @Nullable OffsetDateTime from, @Nullable OffsetDateTime to,
                                      UUID changedBy, @Nullable UUID approvedBy, String reason) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO config_versions (key, selector, value, effective_from, effective_to, changed_by, approved_by, reason)
                VALUES (?, ?, ?::jsonb, COALESCE(CAST(? AS timestamptz), now()), ?, ?, ?, ?) RETURNING id""",
                key, selector, valueJson, from, to, changedBy, approvedBy, reason);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The value in force for exactly this selector, or null. */
    private Object exact(Connection c, String key, String selector) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT value::text FROM config_current WHERE key = ? AND selector = ?", key, selector);
             var rs = ps.executeQuery()) {
            return rs.next() ? parse(rs.getString(1)) : null;
        }
    }

    private static void lockKey(Connection c, String key, String selector) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "config:" + key + ":" + selector); var rs = ps.executeQuery()) {
            rs.next();
        }
    }

    private static void window(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
        var now = OffsetDateTime.now();
        if (from != null && from.isBefore(now.minus(BACKDATE_SLACK))) {
            throw Problems.badRequest("effectiveFrom cannot be in the past: history is not rewritten");
        }
        var start = from == null || from.isBefore(now) ? now : from;
        if (to != null && !to.isAfter(start)) throw Problems.badRequest("effectiveTo must be after effectiveFrom and now");
    }

    private static String reason(@Nullable String reason) {
        var r = reason == null ? "" : reason.strip();
        if (r.length() < MIN_REASON) throw Problems.badRequest("a reason of at least " + MIN_REASON + " characters is required");
        if (r.length() > MAX_REASON) throw Problems.badRequest("reason is at most " + MAX_REASON + " characters");
        return r;
    }

    private Object parse(@Nullable String jsonText) {
        if (jsonText == null) return null;
        try {
            return json.readValue(jsonText, Argument.OBJECT_ARGUMENT);
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
