package in.brand.engage.admin.exports;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.PublicEndpoint;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.auth.Tokens;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.segments.SegmentCompiler;
import in.brand.engage.admin.segments.SegmentDsl;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exports (P6-T08, 03-auth-and-rbac.md): ANALYST and up, {@value #DAILY_LIMIT}
 * per operator per 24 h, at most {@value #MAX_ROWS} rows, every one audited
 * with its row count.
 *
 * <ul>
 * <li>{@code campaign_report}: recipient outcomes by bucket, state and reason,
 *     with delivery, clicks and cost. Aggregate; never identifies anyone.</li>
 * <li>{@code segment_ids}: the segment's identity ids. With
 *     {@code includePii}, their phones and emails too: OWNER only, with a
 *     reason, logged in {@code pii_unmask_log} and counted toward the unmask
 *     limit.</li>
 * <li>{@code sends}: sends between two IST dates (31 days at most), with the
 *     policy reason. Identity ids, never contact details.</li>
 * </ul>
 *
 * The file is generated at once and kept 24 h. It is fetched through a
 * 15-minute signed link that only the requester can mint; the link itself is
 * the credential, so a plain browser download works without a bearer header.
 */
@Controller("/api/exports")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ExportController {

    static final int DAILY_LIMIT = 3;
    static final int MAX_ROWS = 50_000;
    static final int MAX_DAYS = 31;
    static final Duration LINK_TTL = Duration.ofMinutes(15);
    static final String PURPOSE = "export-download";

    @Serdeable
    public record ExportRequest(@Nullable String kind, @Nullable Map<String, Object> params,
                                @Nullable Boolean includePii, @Nullable String reason) {}

    private record Query(String sql, List<Object> params) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final PiiAccess pii;
    private final Tokens tokens;
    private final ObjectMapper json;

    public ExportController(Db db, CurrentOperator current, AuditLog audit, PiiAccess pii, Tokens tokens, ObjectMapper json) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.pii = pii;
        this.tokens = tokens;
        this.json = json;
    }

    @RequiresRole(Role.ANALYST)
    @Post
    public HttpResponse<Map<String, Object>> create(@Body ExportRequest body) {
        if (body == null || body.kind() == null) throw Problems.badRequest("kind is required");
        var params = body.params() == null ? Map.<String, Object>of() : body.params();
        boolean withPii = Boolean.TRUE.equals(body.includePii());
        String reason = null;
        if (withPii) {
            if (!body.kind().equals("segment_ids")) throw Problems.badRequest("only segment_ids can include contact details");
            current.require("OWNER");
            reason = PiiAccess.reason(body.reason());
        }
        var actor = current.id();
        var why = reason;
        Map<String, Object> created = db.inTx(c -> {
            limit(c, actor);
            var q = query(c, body.kind(), params, withPii);
            var id = UUID.randomUUID();
            if (withPii) pii.record(c, actor, null, "phone", "export " + id + ": " + why);
            Sql.update(c, "SET LOCAL statement_timeout = '60s'");
            var csv = new StringBuilder();
            int rows;
            try (var ps = Sql.prepare(c, q.sql() + " LIMIT " + (MAX_ROWS + 1), q.params().toArray());
                 var rs = ps.executeQuery()) {
                rows = Csv.write(rs, csv);
            }
            if (rows > MAX_ROWS) {
                throw new Problems.ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "export-too-large",
                        "exports hold at most " + MAX_ROWS + " rows; narrow it");
            }
            Sql.update(c, """
                    INSERT INTO exports (id, requested_by, kind, params, includes_pii, reason, row_count, status, file_ref)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'ready', ?)""",
                    id, actor, body.kind(), toJson(params), withPii, why, rows, "db:" + id);
            Sql.update(c, "INSERT INTO export_files (export_id, content) VALUES (?, ?)",
                    id, csv.toString().getBytes(StandardCharsets.UTF_8));
            var after = new LinkedHashMap<String, Object>();
            after.put("kind", body.kind());
            after.put("params", params);
            after.put("rows", rows);
            after.put("includesPii", withPii);
            audit.record(c, actor, "export.create", "export", id.toString(), null, toJson(after));
            return one(c, id);
        });
        return HttpResponse.created(created);
    }

    /** Your own exports; an OWNER sees everyone's. */
    @RequiresRole(Role.ANALYST)
    @Get
    public List<Map<String, Object>> list() {
        var actor = current.id();
        boolean all = current.has("OWNER");
        return db.inTx(c -> Rows.list(c, this::parse, SELECT
                + (all ? "" : " WHERE e.requested_by = ?") + " ORDER BY e.created_at DESC LIMIT 50",
                all ? new Object[0] : new Object[] {actor}));
    }

    /** A 15-minute download link. Only the requester can mint one. */
    @RequiresRole(Role.ANALYST)
    @Post("/{id}/link")
    public Map<String, Object> link(@PathVariable String id) {
        var exportId = uuid(id);
        var actor = current.id();
        db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT requested_by, status, expires_at > now() FROM exports WHERE id = ?""", exportId);
                 var rs = ps.executeQuery()) {
                if (!rs.next() || !actor.equals(rs.getObject(1, UUID.class))) throw Problems.notFound("no such export");
                if (!"ready".equals(rs.getString(2)) || !rs.getBoolean(3)) {
                    throw Problems.conflict("export-expired", "this export has expired; create a new one");
                }
            }
            return null;
        });
        var token = tokens.issuePurpose(actor, PURPOSE, Map.of("export", exportId.toString()), LINK_TTL);
        return Map.<String, Object>of("url", "/api/exports/download?token=" + token,
                "expiresAt", OffsetDateTime.now().plus(LINK_TTL));
    }

    @PublicEndpoint("authorised by a 15-minute signed link minted for the requester")
    @Get("/download")
    public HttpResponse<byte[]> download(@Nullable @QueryValue String token) {
        Map<String, Object> claims;
        try {
            claims = tokens.verifyPurpose(token == null ? "" : token, PURPOSE);
        } catch (Tokens.InvalidToken e) {
            throw Problems.unauthorized("this download link has expired; get a new one");
        }
        UUID operator, exportId;
        try {
            operator = UUID.fromString(String.valueOf(claims.get("sub")));
            exportId = UUID.fromString(String.valueOf(claims.get("export")));
        } catch (IllegalArgumentException e) {
            throw Problems.unauthorized("this download link is not valid");
        }
        return db.inTx(c -> {
            String kind;
            byte[] content;
            try (var ps = Sql.prepare(c, """
                    SELECT e.kind, f.content FROM exports e
                      JOIN export_files f ON f.export_id = e.id
                      JOIN operators o ON o.id = e.requested_by
                     WHERE e.id = ? AND e.requested_by = ? AND e.status = 'ready' AND e.expires_at > now()
                       AND o.status = 'active'""", exportId, operator);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("this export has expired or does not exist");
                kind = rs.getString(1);
                content = rs.getBytes(2);
            }
            audit.record(c, operator, "export.download", "export", exportId.toString(), null, null);
            return HttpResponse.ok(content)
                    .contentType(new MediaType("text/csv; charset=utf-8"))
                    .header("Content-Disposition", "attachment; filename=\"" + kind + "-" + LocalDate.now() + ".csv\"")
                    .header("Cache-Control", "no-store");
        });
    }

    /* ------------------------------------------------------------------ */

    private static final String SELECT = """
            SELECT e.id, e.kind, e.params, e.includes_pii, e.row_count, e.status, e.created_at, e.expires_at,
                   o.email AS requested_by
              FROM exports e JOIN operators o ON o.id = e.requested_by""";

    private Map<String, Object> one(Connection c, UUID id) throws SQLException {
        return Rows.list(c, this::parse, SELECT + " WHERE e.id = ?", id).getFirst();
    }

    /** Serialised per operator: the 4th export in 24 h is refused even from two tabs at once. */
    private static void limit(Connection c, UUID actor) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "export:" + actor);
             var rs = ps.executeQuery()) {
            rs.next();
        }
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM exports WHERE requested_by = ? AND created_at > now() - interval '24 hours'""", actor);
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) >= DAILY_LIMIT) {
                throw new Problems.ApiException(HttpStatus.TOO_MANY_REQUESTS, "export-limit",
                        DAILY_LIMIT + " exports in 24 hours is the limit");
            }
        }
    }

    private Query query(Connection c, String kind, Map<String, Object> params, boolean withPii) throws SQLException {
        return switch (kind) {
            case "campaign_report" -> {
                var id = idParam(params, "campaignId");
                exists(c, "SELECT 1 FROM campaigns WHERE id = ?", id, "no such campaign");
                yield new Query("""
                        SELECT r.bucket, r.state, COALESCE(r.block_reason, '') AS reason, count(*) AS recipients,
                               count(s.delivered_at) AS delivered, count(s.clicked_at) AS clicked,
                               COALESCE(sum(s.cost_paise), 0) AS cost_paise
                          FROM campaign_recipients r LEFT JOIN sends s ON s.id = r.send_id
                         WHERE r.campaign_id = ? GROUP BY 1, 2, 3 ORDER BY 1, 2, 3""", List.of(id));
            }
            case "segment_ids" -> {
                var id = idParam(params, "segmentId");
                String definition;
                try (var ps = Sql.prepare(c, "SELECT definition::text FROM segments WHERE id = ?", id);
                     var rs = ps.executeQuery()) {
                    if (!rs.next()) throw Problems.badRequest("no such segment");
                    definition = rs.getString(1);
                }
                var where = SegmentCompiler.where(SegmentDsl.parse(parse(definition)));
                var contact = withPii ? """
                        , (SELECT string_agg(k.value, ' ') FROM identity_keys k WHERE k.identity_id = i.id AND k.kind = 'phone') AS phone
                        , (SELECT string_agg(k.value, ' ') FROM identity_keys k WHERE k.identity_id = i.id AND k.kind = 'email') AS email"""
                        : "";
                yield new Query("SELECT i.id AS identity_id" + contact + " FROM identities i WHERE " + where.sql()
                        + " ORDER BY i.id", where.params());
            }
            case "sends" -> {
                var from = dateParam(params, "from");
                var to = dateParam(params, "to");
                if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                    throw Problems.badRequest("from..to is 1 to " + MAX_DAYS + " days");
                }
                var args = new ArrayList<Object>(List.of(from.toString(), to.toString()));
                yield new Query("""
                        SELECT s.id, s.identity_id, s.channel::text AS channel, s.category::text AS category, s.template_key,
                               s.intent_key, s.status::text AS status, s.decision->>'reason' AS reason, s.cost_paise,
                               s.created_at, s.sent_at, s.delivered_at, s.read_at, s.clicked_at
                          FROM sends s
                         WHERE s.created_at >= CAST(? AS date)::timestamp AT TIME ZONE 'Asia/Kolkata'
                           AND s.created_at < (CAST(? AS date) + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'
                         ORDER BY s.id""", args);
            }
            default -> throw Problems.badRequest("kind is campaign_report, segment_ids or sends");
        };
    }

    private static UUID idParam(Map<String, Object> params, String name) {
        try {
            return UUID.fromString(String.valueOf(params.get(name)));
        } catch (IllegalArgumentException e) {
            throw Problems.badRequest(name + " is required");
        }
    }

    private static LocalDate dateParam(Map<String, Object> params, String name) {
        try {
            return LocalDate.parse(String.valueOf(params.get(name)));
        } catch (DateTimeParseException e) {
            throw Problems.badRequest(name + " is a date, YYYY-MM-DD");
        }
    }

    private static void exists(Connection c, String sql, UUID id, String message) throws SQLException {
        try (var ps = Sql.prepare(c, sql, id); var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.badRequest(message);
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such export");
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
