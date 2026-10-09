package in.brand.engage.admin.segments;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Segments (P6-T05): named audiences in the {@link SegmentDsl}, compiled on
 * the server by {@link SegmentCompiler}. The browser never sends SQL.
 *
 * <p>Every count runs with {@code statement_timeout = 10s}; a definition that
 * needs longer answers 422 rather than holding a connection. Saving or
 * editing a segment needs CAMPAIGN_EDIT and is audited; sizes are aggregate
 * counts only, never identities.
 */
@Controller("/api/segments")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SegmentController {

    static final String TIMEOUT = "10s";

    @Serdeable
    public record SegmentWrite(@Nullable String name, @Nullable String description, @Nullable Object definition) {}

    @Serdeable
    public record Preview(@Nullable Object definition) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final ObjectMapper json;

    public SegmentController(Db db, CurrentOperator current, AuditLog audit, ObjectMapper json) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.json = json;
    }

    /** The builder's palette: every field, its operators and value kind, and which are not available yet. */
    @RequiresRole(Role.VIEWER)
    @Get("/fields")
    public List<Predicates.Field> fields() {
        return Predicates.fields();
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public List<Map<String, Object>> list() {
        return db.inTx(c -> Rows.list(c, this::parse, SELECT + " ORDER BY s.name"));
    }

    @RequiresRole(Role.VIEWER)
    @Get("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return db.inTx(c -> one(c, uuid(id)));
    }

    /** Count a definition without saving it: the builder's live size. */
    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post("/preview")
    public Map<String, Object> preview(@Body Preview body) {
        var tree = SegmentDsl.parse(body == null ? null : body.definition());
        return Map.of("size", size(tree));
    }

    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post
    public HttpResponse<Map<String, Object>> create(@Body SegmentWrite body) {
        var w = validated(body);
        var size = size(w.tree());
        var actor = current.id();
        var id = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM segments WHERE lower(name) = lower(?)", w.name());
                 var rs = ps.executeQuery()) {
                if (rs.next()) throw Problems.conflict("segment-exists", "a segment called " + w.name() + " exists");
            }
            UUID newId;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO segments (name, description, definition, last_size, last_sized_at, created_by)
                    VALUES (?, ?, ?::jsonb, ?, now(), ?) RETURNING id""",
                    w.name(), w.description(), w.definitionJson(), (int) size, actor);
                 var rs = ps.executeQuery()) {
                rs.next();
                newId = rs.getObject(1, UUID.class);
            }
            audit.record(c, actor, "segment.create", "segment", newId.toString(), null,
                    toJson(Map.of("name", w.name(), "definition", body.definition(), "size", size)));
            return newId;
        });
        Map<String, Object> saved = db.inTx(c -> one(c, id));
        return HttpResponse.created(saved);
    }

    /** Campaigns freeze their audience when estimated (P6-T06), so editing a segment never changes a sent one. */
    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Put("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Body SegmentWrite body) {
        var segmentId = uuid(id);
        var w = validated(body);
        var size = size(w.tree());
        var actor = current.id();
        return db.inTx(c -> {
            var before = one(c, segmentId);
            try (var ps = Sql.prepare(c, "SELECT 1 FROM segments WHERE lower(name) = lower(?) AND id <> ?",
                    w.name(), segmentId); var rs = ps.executeQuery()) {
                if (rs.next()) throw Problems.conflict("segment-exists", "a segment called " + w.name() + " exists");
            }
            Sql.update(c, """
                    UPDATE segments SET name = ?, description = ?, definition = ?::jsonb, last_size = ?,
                                        last_sized_at = now(), updated_at = now()
                     WHERE id = ?""", w.name(), w.description(), w.definitionJson(), (int) size, segmentId);
            audit.record(c, actor, "segment.update", "segment", segmentId.toString(),
                    toJson(Map.of("name", before.get("name"), "definition", before.get("definition"))),
                    toJson(Map.of("name", w.name(), "definition", body.definition(), "size", size)));
            return one(c, segmentId);
        });
    }

    /** Recount a saved segment (sizes drift as customers order and opt out). */
    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post("/{id}/size")
    public Map<String, Object> resize(@PathVariable String id) {
        var segmentId = uuid(id);
        var definition = db.inTx(c -> one(c, segmentId)).get("definition");
        var size = size(SegmentDsl.parse(definition));
        return db.inTx(c -> {
            Sql.update(c, "UPDATE segments SET last_size = ?, last_sized_at = now() WHERE id = ?", (int) size, segmentId);
            return one(c, segmentId);
        });
    }

    /* ------------------------------------------------------------------ */

    private static final String SELECT = """
            SELECT s.id, s.name, s.description, s.definition, s.last_size, s.last_sized_at,
                   o.email AS created_by, s.created_at, s.updated_at
              FROM segments s LEFT JOIN operators o ON o.id = s.created_by""";

    private record Validated(String name, String description, SegmentDsl tree, String definitionJson) {}

    private Validated validated(SegmentWrite body) {
        if (body == null) throw Problems.badRequest("name and definition are required");
        var name = body.name() == null ? "" : body.name().strip();
        if (name.isEmpty() || name.length() > 100) throw Problems.badRequest("a name of 1 to 100 characters is required");
        var description = body.description() == null || body.description().isBlank() ? null : body.description().strip();
        if (description != null && description.length() > 500) throw Problems.badRequest("description is at most 500 characters");
        var tree = SegmentDsl.parse(body.definition());
        SegmentCompiler.where(tree);                               // every field and value checked before saving
        return new Validated(name, description, tree, toJson(body.definition()));
    }

    /** The audience size, under the statement timeout. */
    long size(SegmentDsl tree) {
        var q = SegmentCompiler.count(tree);
        try {
            return db.inTx(c -> {
                Sql.update(c, "SET LOCAL statement_timeout = '" + TIMEOUT + "'");
                try (var ps = Sql.prepare(c, q.sql(), q.params().toArray()); var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            });
        } catch (Db.DbException e) {
            if ("57014".equals(e.sqlState())) {
                throw new Problems.ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "segment-too-slow",
                        "counting this segment took over " + TIMEOUT + "; narrow it");
            }
            throw e;
        }
    }

    private Map<String, Object> one(Connection c, UUID id) throws SQLException {
        var rows = Rows.list(c, this::parse, SELECT + " WHERE s.id = ?", id);
        if (rows.isEmpty()) throw Problems.notFound("no such segment");
        return rows.getFirst();
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such segment");
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
