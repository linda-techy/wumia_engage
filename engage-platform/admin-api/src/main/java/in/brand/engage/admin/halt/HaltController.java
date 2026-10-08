package in.brand.engage.admin.halt;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kill switches (P6-T02). A halt is a {@code config_versions} row for
 * {@code halt.channel}, {@code halt.marketing} or {@code halt.journey} = true.
 * Policy reads those uncached on every decision (KillSwitch), so the next
 * decision on any instance sees it; there is nothing to propagate.
 *
 * <ul>
 * <li>Halting needs no second approver, whatever the key's risk tier:
 *     stopping is always cheap to approve. CAMPAIGN_SEND or CONFIG_ADMIN.</li>
 * <li>Halting also pauses RUNNING and SCHEDULED campaigns on that scope, so
 *     the state is visible and a scheduled campaign does not start into the
 *     halt and spend its audience on blocked sends. Releasing the halt does
 *     not resume them: resuming is a deliberate campaign action.</li>
 * <li>Releasing follows the key's risk tier and needs CONFIG_ADMIN. A
 *     CRITICAL halt key would need a config proposal (P6-T03); the V8 keys
 *     are GUARDED.</li>
 * </ul>
 */
@Controller("/api/halt")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HaltController {

    private static final Pattern JOURNEY = Pattern.compile("[a-z0-9_]{1,64}");
    static final int MAX_REASON = 500;

    @Serdeable
    public record HaltRequest(String scope, @Nullable String selector, String reason) {}

    @Serdeable
    public record Halt(String scope, String selector, OffsetDateTime since, @Nullable String byEmail, String reason) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final ObjectMapper json;

    public HaltController(Db db, CurrentOperator current, AuditLog audit, ObjectMapper json) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.json = json;
    }

    /** Every halt in force. The console shows these in its header on every page. */
    @RequiresRole(Role.VIEWER)
    @Get
    public List<Halt> list() {
        return db.inTx(c -> {
            var out = new ArrayList<Halt>();
            try (var ps = Sql.prepare(c, """
                    SELECT substr(v.key, 6) AS scope, v.selector, v.effective_from, o.email, v.reason
                      FROM config_current v LEFT JOIN operators o ON o.id = v.changed_by
                     WHERE v.key IN ('halt.channel', 'halt.marketing', 'halt.journey') AND v.value = 'true'::jsonb
                     ORDER BY v.effective_from DESC""");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Halt(rs.getString("scope"), rs.getString("selector"),
                            Sql.timestamp(rs, "effective_from"), rs.getString("email"), rs.getString("reason")));
                }
            }
            return out;
        });
    }

    @RequiresRole({Role.CAMPAIGN_SEND, Role.CONFIG_ADMIN})
    @Post
    public HttpResponse<Map<String, Object>> halt(@Body HaltRequest body) {
        if (body == null) throw Problems.badRequest("scope, selector and reason are required");
        var scope = scope(body.scope());
        var selector = selector(scope, body.selector());
        var reason = reason(body.reason());
        var key = "halt." + scope;
        var actor = current.id();

        return db.inTx(c -> {
            lock(c);
            if (exactValue(c, key, selector)) {
                return HttpResponse.ok(result(scope, selector, true, List.of()));
            }
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES (?, ?, 'true'::jsonb, ?, ?)""", key, selector, actor, reason);
            var paused = pauseCampaigns(c, scope, selector);
            for (var id : paused) {
                audit.record(c, actor, "campaign.pause", "campaign", id.toString(), null,
                        toJson(Map.of("cause", key + ":" + selector, "reason", reason)));
            }
            audit.record(c, actor, "halt.set", "config", key + ":" + selector, null,
                    toJson(Map.of("value", true, "reason", reason,
                            "pausedCampaigns", paused.stream().map(UUID::toString).toList())));
            return HttpResponse.created(result(scope, selector, false, paused));
        });
    }

    @RequiresRole(Role.CONFIG_ADMIN)
    @Delete("/{scope}/{selector}")
    public Map<String, Object> release(@PathVariable String scope, @PathVariable String selector,
                                       @Nullable @QueryValue String reason) {
        var s = scope(scope);
        var sel = selector(s, selector);
        var why = reason(reason);
        var key = "halt." + s;
        var actor = current.id();

        db.inTx(c -> {
            lock(c);
            try (var ps = Sql.prepare(c, "SELECT risk FROM config_keys WHERE key = ?", key); var rs = ps.executeQuery()) {
                rs.next();
                if ("CRITICAL".equals(rs.getString(1))) {
                    throw Problems.conflict("approval-required", key + " is CRITICAL: release it through a config proposal");
                }
            }
            if (!exactValue(c, key, sel)) throw Problems.notFound(key + " is not set for " + sel);
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES (?, ?, 'false'::jsonb, ?, ?)""", key, sel, actor, why);
            audit.record(c, actor, "halt.release", "config", key + ":" + sel,
                    toJson(Map.of("value", true)), toJson(Map.of("value", false, "reason", why)));
            return null;
        });
        return Map.of("scope", s, "selector", sel, "halted", false);
    }

    /** One halt change at a time, so the already-halted check cannot race. */
    private static void lock(Connection c) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended('config:halt', 0))");
             var rs = ps.executeQuery()) {
            rs.next();
        }
    }

    /** The value in force for exactly this selector ('*' does not count for 'push'). */
    private static boolean exactValue(Connection c, String key, String selector) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT value = 'true'::jsonb FROM config_current WHERE key = ? AND selector = ?""", key, selector);
             var rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static List<UUID> pauseCampaigns(Connection c, String scope, String selector) throws SQLException {
        if (scope.equals("journey")) return List.of();       // campaigns are not journeys
        var sql = """
                UPDATE campaigns SET status = 'PAUSED', updated_at = now()
                 WHERE status IN ('RUNNING', 'SCHEDULED')""";
        var byChannel = scope.equals("channel") && !selector.equals("*");
        var out = new ArrayList<UUID>();
        try (var ps = byChannel
                ? Sql.prepare(c, sql + " AND channel = CAST(? AS channel) RETURNING id", selector)
                : Sql.prepare(c, sql + " RETURNING id");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getObject(1, UUID.class));
        }
        return out;
    }

    private static Map<String, Object> result(String scope, String selector, boolean already, List<UUID> paused) {
        var out = new LinkedHashMap<String, Object>();
        out.put("scope", scope);
        out.put("selector", selector);
        out.put("halted", true);
        out.put("alreadyHalted", already);
        out.put("pausedCampaigns", paused.stream().map(UUID::toString).toList());
        return out;
    }

    private static String scope(String scope) {
        var s = scope == null ? "" : scope.strip().toLowerCase(Locale.ROOT);
        if (!List.of("channel", "marketing", "journey").contains(s)) {
            throw Problems.badRequest("scope must be channel, marketing or journey");
        }
        return s;
    }

    private static String selector(String scope, @Nullable String selector) {
        var s = selector == null || selector.isBlank() ? "*" : selector.strip().toLowerCase(Locale.ROOT);
        switch (scope) {
            case "marketing" -> {
                if (!s.equals("*")) throw Problems.badRequest("marketing is halted everywhere: selector must be *");
            }
            case "channel" -> {
                if (!s.equals("*") && Arrays.stream(Channel.values()).noneMatch(ch -> ch.dbName().equals(s))) {
                    throw Problems.badRequest("unknown channel " + s);
                }
            }
            default -> {
                if (!s.equals("*") && !JOURNEY.matcher(s).matches()) throw Problems.badRequest("invalid journey key");
            }
        }
        return s;
    }

    private static String reason(@Nullable String reason) {
        if (reason == null || reason.isBlank()) throw Problems.badRequest("a reason is required");
        if (reason.length() > MAX_REASON) throw Problems.badRequest("reason is at most " + MAX_REASON + " characters");
        return reason.strip();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
