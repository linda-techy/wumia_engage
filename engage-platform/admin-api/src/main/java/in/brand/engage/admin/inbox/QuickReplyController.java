package in.brand.engage.admin.inbox;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;

/**
 * Saved answers for the inbox (V50): staff tap one to put it in the reply box,
 * with {name} filled in, then edit and send. The people who answer customers
 * keep them up to date, so ANALYST may edit; every change is audited.
 */
@Controller("/api/inbox/quick-replies")
@ExecuteOn(TaskExecutors.BLOCKING)
public class QuickReplyController {

    @Serdeable
    public record QuickReply(String title, String body, Integer sort) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;

    public QuickReplyController(Db db, CurrentOperator current, AuditLog audit) {
        this.db = db;
        this.current = current;
        this.audit = audit;
    }

    @RequiresRole(Role.ANALYST)
    @Get
    public List<Map<String, Object>> list() {
        return db.inTx(c -> Rows.list(c, "SELECT id, title, body, sort FROM inbox_quick_replies ORDER BY sort, title"));
    }

    @RequiresRole(Role.ANALYST)
    @Post
    public Map<String, Object> create(@Body QuickReply body) {
        var q = valid(body);
        var actor = current.id();
        return db.inTx(c -> {
            long id;
            try (var ps = Sql.prepare(c, "INSERT INTO inbox_quick_replies (title, body, sort, updated_by) VALUES (?, ?, ?, ?) RETURNING id",
                    q.title(), q.body(), q.sort(), actor); var rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
            audit.record(c, actor, "inbox.quick_reply.create", "quick_reply", String.valueOf(id), null, null);
            return one(c, id);
        });
    }

    @RequiresRole(Role.ANALYST)
    @Put("/{id}")
    public Map<String, Object> update(@PathVariable long id, @Body QuickReply body) {
        var q = valid(body);
        var actor = current.id();
        return db.inTx(c -> {
            int n = Sql.update(c, "UPDATE inbox_quick_replies SET title = ?, body = ?, sort = ?, updated_by = ?, updated_at = now() WHERE id = ?",
                    q.title(), q.body(), q.sort(), actor, id);
            if (n == 0) throw Problems.notFound("no such saved answer");
            audit.record(c, actor, "inbox.quick_reply.update", "quick_reply", String.valueOf(id), null, null);
            return one(c, id);
        });
    }

    @RequiresRole(Role.ANALYST)
    @Delete("/{id}")
    public HttpResponse<Void> delete(@PathVariable long id) {
        var actor = current.id();
        db.inTx(c -> {
            if (Sql.update(c, "DELETE FROM inbox_quick_replies WHERE id = ?", id) > 0) {
                audit.record(c, actor, "inbox.quick_reply.delete", "quick_reply", String.valueOf(id), null, null);
            }
            return null;
        });
        return HttpResponse.noContent();
    }

    static QuickReply valid(QuickReply b) {
        var title = b == null || b.title() == null ? "" : b.title().strip();
        var text = b == null || b.body() == null ? "" : b.body().strip();
        if (title.isEmpty() || title.length() > 40) throw Problems.badRequest("give it a title of 1 to 40 characters");
        if (text.isEmpty() || text.length() > 1024) throw Problems.badRequest("write an answer of 1 to 1024 characters");
        return new QuickReply(title, text, b.sort() == null ? 100 : b.sort());
    }

    private static Map<String, Object> one(java.sql.Connection c, long id) throws java.sql.SQLException {
        return Rows.list(c, "SELECT id, title, body, sort FROM inbox_quick_replies WHERE id = ?", id).getFirst();
    }
}
