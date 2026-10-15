package in.brand.engage.admin.support;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Support requests (V33): customers' replies that staff act on by hand, such
 * as a new delivery time or address after a failed delivery (owner's decision
 * 2026-10-15: the courier is updated manually, in Shiprocket).
 *
 * <ul>
 * <li>{@code GET /api/support-requests?status=open|done}: the list, with order
 *     number, AWB and courier; no contact details and no reply text. VIEWER.</li>
 * <li>{@code POST /api/support-requests/{id}/reply}: the reply text. It often
 *     holds an address, so it goes through {@link PiiAccess} (logged, counted
 *     in the operator's daily limit). ANALYST.</li>
 * <li>{@code POST /api/support-requests/{id}/resolve}: done, with an optional
 *     note; audited. ANALYST.</li>
 * </ul>
 */
@Controller("/api/support-requests")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SupportController {

    @Serdeable
    public record Request(long id, String kind, String status, String identityId, String orderId, String orderNumber,
                          String awb, String courier, int messages, boolean hasText, String note, String resolvedBy,
                          OffsetDateTime resolvedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    @Serdeable
    public record Reply(long id, String text) {}

    @Serdeable
    public record Resolve(@Nullable String note) {}

    private final Db db;
    private final CurrentOperator current;
    private final PiiAccess pii;
    private final AuditLog audit;
    private final ObjectMapper json;

    public SupportController(Db db, CurrentOperator current, PiiAccess pii, AuditLog audit, ObjectMapper json) {
        this.json = json;
        this.db = db;
        this.current = current;
        this.pii = pii;
        this.audit = audit;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public List<Request> list(@QueryValue(defaultValue = "open") String status) {
        if (!status.equals("open") && !status.equals("done")) throw Problems.badRequest("status is open or done");
        return db.inTx(c -> {
            var out = new ArrayList<Request>();
            try (var ps = Sql.prepare(c, """
                    SELECT r.id, r.kind, r.status, r.identity_id::text AS identity_id, r.order_id,
                           o.order_number, sh.awb, sh.carrier, cardinality(r.message_ids) AS messages,
                           r.message_text IS NOT NULL AS has_text, r.note, op.email AS resolved_by, r.resolved_at,
                           r.created_at, r.updated_at
                      FROM support_requests r
                      LEFT JOIN orders o ON o.id = r.order_id
                      LEFT JOIN shipments sh ON sh.id = r.shipment_id
                      LEFT JOIN operators op ON op.id = r.resolved_by
                     WHERE r.status = ?
                     ORDER BY CASE WHEN r.status = 'open' THEN r.created_at END ASC,
                              r.resolved_at DESC NULLS LAST
                     LIMIT 200""", status);
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Request(rs.getLong("id"), rs.getString("kind"), rs.getString("status"),
                            rs.getString("identity_id"), rs.getString("order_id"), rs.getString("order_number"),
                            rs.getString("awb"), rs.getString("carrier"), rs.getInt("messages"), rs.getBoolean("has_text"),
                            rs.getString("note"), rs.getString("resolved_by"), Sql.timestamp(rs, "resolved_at"),
                            Sql.timestamp(rs, "created_at"), Sql.timestamp(rs, "updated_at")));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.ANALYST)
    @Post("/{id}/reply")
    public Reply reply(@PathVariable long id) {
        var operator = current.id();
        return db.inTx(c -> {
            UUID identity;
            String text;
            try (var ps = Sql.prepare(c, "SELECT identity_id, message_text FROM support_requests WHERE id = ?", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no support request " + id);
                identity = rs.getObject(1, UUID.class);
                text = rs.getString(2);
            }
            if (text == null) throw Problems.notFound("support request " + id + " has no reply text (none, or cleared after 30 days)");
            pii.record(c, operator, identity, "support_reply", "support request " + id);
            return new Reply(id, text);
        });
    }

    @RequiresRole(Role.ANALYST)
    @Post("/{id}/resolve")
    public Request resolve(@PathVariable long id, @Nullable @Body Resolve body) {
        var operator = current.id();
        var note = body == null || body.note() == null ? null : body.note().strip();
        if (note != null && note.length() > 1000) throw Problems.badRequest("note is at most 1000 characters");
        db.inTx(c -> {
            int n = Sql.update(c, """
                    UPDATE support_requests SET status = 'done', note = ?, resolved_by = ?, resolved_at = now(), updated_at = now()
                     WHERE id = ? AND status = 'open'""", note == null || note.isEmpty() ? null : note, operator, id);
            if (n == 0) {
                try (var ps = Sql.prepare(c, "SELECT 1 FROM support_requests WHERE id = ?", id); var rs = ps.executeQuery()) {
                    if (!rs.next()) throw Problems.notFound("no support request " + id);
                }
                throw Problems.conflict("already-resolved", "support request " + id + " is already done");
            }
            audit.record(c, operator, "support.resolve", "support_request", Long.toString(id), null,
                    toJson(note == null || note.isEmpty() ? java.util.Map.of() : java.util.Map.of("note", note)));
            return null;
        });
        return list("done").stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> Problems.notFound("no support request " + id));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
