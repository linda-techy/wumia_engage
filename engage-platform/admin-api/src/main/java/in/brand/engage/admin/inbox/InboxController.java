package in.brand.engage.admin.inbox;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.WaInboxLog;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The WhatsApp inbox (V49): the support number lives in Engage, so staff read
 * customers' messages and answer them here, on a phone browser too.
 *
 * <ul>
 * <li>Inside the 24 h window the customer opened, a reply is free-form and
 *     free of Meta charges.</li>
 * <li>After it, only an approved template may open the conversation again:
 *     {@link #FOLLOW_UPS}, utility customer-care follow-ups.</li>
 * <li>Engage's own automated messages appear in the same thread, read-only.</li>
 * </ul>
 * Reading a thread shows the customer's number, so it needs ANALYST (contact
 * details) and every open is recorded like any other contact-detail view.
 */
@Controller("/api/inbox")
@ExecuteOn(TaskExecutors.BLOCKING)
public class InboxController {

    /** Templates staff may send when the window has closed: utility customer-care follow-ups only. */
    static final Set<String> FOLLOW_UPS = Set.of("support_followup_v1");
    static final int TEXT_MAX = 4096;

    @Serdeable
    public record ReplyBody(String text) {}

    @Serdeable
    public record TemplateBody(String templateKey) {}

    @Serdeable
    public record StatusBody(String status) {}

    private final Db db;
    private final CurrentOperator current;
    private final PiiAccess pii;
    private final AuditLog audit;
    private final WhatsAppGraph wa;
    private final Clock clock;

    public InboxController(Db db, CurrentOperator current, PiiAccess pii, AuditLog audit, WhatsAppGraph wa) {
        this(db, current, pii, audit, wa, Clock.systemUTC());
    }

    InboxController(Db db, CurrentOperator current, PiiAccess pii, AuditLog audit, WhatsAppGraph wa, Clock clock) {
        this.db = db;
        this.current = current;
        this.pii = pii;
        this.audit = audit;
        this.wa = wa;
        this.clock = clock;
    }

    private static final String THREAD_COLUMNS = """
            SELECT t.identity_id, t.last_message_at, t.last_inbound_at, t.last_preview, t.unread, t.status,
                   COALESCE(NULLIF(p.attrs->>'first_name', ''), NULLIF(p.attrs->>'wa_name', '')) AS name,
                   (SELECT k.value FROM identity_keys k WHERE k.identity_id = t.identity_id AND k.kind = 'phone' LIMIT 1) AS phone,
                   p.wa_window_until AS window_until
              FROM wa_threads t LEFT JOIN profiles p ON p.identity_id = t.identity_id""";

    /** Conversations, newest first: open (default), done or all; {@code q} matches a name or number. */
    @RequiresRole(Role.ANALYST)
    @Get
    public List<Map<String, Object>> threads(@QueryValue(defaultValue = "open") String status, @Nullable @QueryValue String q) {
        if (!Set.of("open", "done", "all").contains(status)) throw Problems.badRequest("status is open, done or all");
        var like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(java.util.Locale.ROOT) + "%";
        return db.inTx(c -> Rows.list(c, THREAD_COLUMNS + "\n" + """
                 WHERE (? = 'all' OR t.status = ?)
                   AND (CAST(? AS text) IS NULL
                        OR lower(COALESCE(p.attrs->>'first_name', '') || ' ' || COALESCE(p.attrs->>'wa_name', '')) LIKE ?
                        OR EXISTS (SELECT 1 FROM identity_keys k WHERE k.identity_id = t.identity_id AND k.kind = 'phone' AND k.value LIKE ?))
                 ORDER BY t.unread > 0 DESC, t.last_message_at DESC LIMIT 200""",
                status, status, like, like, like));
    }

    /** For the menu badge: open conversations with unread messages. */
    @RequiresRole(Role.VIEWER)
    @Get("/unread")
    public Map<String, Object> unread() {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*), COALESCE(sum(unread), 0) FROM wa_threads WHERE status = 'open' AND unread > 0");
                 var rs = ps.executeQuery()) {
                rs.next();
                return Map.<String, Object>of("threads", rs.getLong(1), "messages", rs.getLong(2));
            }
        });
    }

    /** One conversation: the customer, the reply window, and the last 300 messages oldest first. */
    @RequiresRole(Role.ANALYST)
    @Get("/{identityId}")
    public Map<String, Object> thread(@PathVariable String identityId) {
        var id = uuid(identityId);
        var actor = current.id();
        return db.inTx(c -> {
            var head = Rows.list(c, THREAD_COLUMNS + " WHERE t.identity_id = ?", id);
            if (head.isEmpty()) throw Problems.notFound("no WhatsApp conversation with this customer");
            pii.record(c, actor, id, "whatsapp_inbox", "WhatsApp inbox");
            var out = new LinkedHashMap<String, Object>(head.getFirst());
            out.put("email", one(c, "SELECT value FROM identity_keys WHERE identity_id = ? AND kind = 'email' LIMIT 1", id));
            out.put("orders", Rows.list(c, """
                    SELECT order_number, total_paise, created_at, cancelled_at IS NOT NULL AS cancelled
                      FROM orders WHERE identity_id = ? ORDER BY created_at DESC LIMIT 5""", id));
            out.put("messages", Rows.list(c, """
                    SELECT * FROM (
                      SELECT m.id, m.direction, m.kind, m.body, m.media_id IS NOT NULL AS has_media, m.media_mime, m.media_name,
                             m.template_key, m.send_id IS NOT NULL AS automated, o.email AS sent_by, m.status, m.failed_reason, m.at
                        FROM wa_messages m LEFT JOIN operators o ON o.id = m.sent_by
                       WHERE m.identity_id = ? ORDER BY m.at DESC LIMIT 300) x ORDER BY at""", id));
            out.put("windowOpen", windowOpen(c, id));
            out.put("followUps", Rows.list(c, """
                    SELECT DISTINCT w.key FROM wa_templates w JOIN templates t ON t.key = w.key
                     WHERE w.key = ANY(?) AND w.status = 'APPROVED' AND w.language = 'en'""", (Object) FOLLOW_UPS.toArray(String[]::new)));
            return out;
        });
    }

    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/read")
    public HttpResponse<Void> read(@PathVariable String identityId) {
        var id = uuid(identityId);
        db.inTx(c -> Sql.update(c, "UPDATE wa_threads SET unread = 0, updated_at = now() WHERE identity_id = ?", id));
        return HttpResponse.noContent();
    }

    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/status")
    public HttpResponse<Void> status(@PathVariable String identityId, @Body StatusBody body) {
        var id = uuid(identityId);
        if (body == null || !Set.of("open", "done").contains(body.status())) throw Problems.badRequest("status is open or done");
        db.inTx(c -> Sql.update(c, """
                UPDATE wa_threads SET status = ?, unread = CASE WHEN ? = 'done' THEN 0 ELSE unread END, updated_at = now()
                 WHERE identity_id = ?""", body.status(), body.status(), id));
        return HttpResponse.noContent();
    }

    /** A free-form reply: only inside the 24 h the customer's last message opened. */
    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/reply")
    public Map<String, Object> reply(@PathVariable String identityId, @Body ReplyBody body) {
        var id = uuid(identityId);
        var text = body == null || body.text() == null ? "" : body.text().strip();
        if (text.isEmpty() || text.length() > TEXT_MAX) throw Problems.badRequest("write a message of 1 to 4096 characters");
        var actor = current.id();
        var to = db.inTx(c -> {
            if (!windowOpen(c, id)) {
                throw Problems.conflict("window-closed", "the 24-hour reply window has closed: send the follow-up template instead");
            }
            return recipient(c, id);
        });
        var wamid = send(() -> wa.sendText(to, text));
        return db.inTx(c -> {
            WaInboxLog.outbound(c, id, wamid, "text", text, null, null, actor, clock.instant());
            Sql.update(c, "UPDATE wa_threads SET unread = 0, updated_at = now() WHERE identity_id = ?", id);
            pii.record(c, actor, id, "whatsapp_reply", "WhatsApp inbox reply");
            return message(c, wamid);
        });
    }

    /** After the window: an approved utility follow-up, which re-opens the conversation when the customer answers. */
    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/template")
    public Map<String, Object> template(@PathVariable String identityId, @Body TemplateBody body) {
        var id = uuid(identityId);
        var key = body == null ? null : body.templateKey();
        if (key == null || !FOLLOW_UPS.contains(key)) throw Problems.badRequest("templateKey is one of " + FOLLOW_UPS);
        var actor = current.id();
        record Ready(String to, String providerName, String name, String rendered) {}
        var ready = db.inTx(c -> {
            String providerName;
            try (var ps = Sql.prepare(c, """
                    SELECT provider_name FROM wa_templates WHERE key = ? AND language = 'en' AND status = 'APPROVED'
                       AND quality <> 'RED' ORDER BY synced_at DESC LIMIT 1""", key); var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.conflict("template-not-approved", key + " is not approved by Meta yet");
                providerName = rs.getString(1);
            }
            var name = String.valueOf(firstName(c, id));
            return new Ready(recipient(c, id), providerName, name,
                    "Hi " + name + ", this is WUMIKA customer care following up on your message. Reply here and we will help you.");
        });
        var wamid = send(() -> wa.sendTemplate(ready.to(), ready.providerName(), "en", List.of(ready.name())));
        return db.inTx(c -> {
            WaInboxLog.outbound(c, id, wamid, "template", ready.rendered(), key, null, actor, clock.instant());
            audit.record(c, actor, "inbox.template", "identity", id.toString(), null, "{\"template\":\"" + key + "\"}");
            return message(c, wamid);
        });
    }

    /** A customer's photo, voice note or document, fetched from Meta on demand (only media the customer sent here). */
    @RequiresRole(Role.ANALYST)
    @Get("/media/{messageId}")
    public HttpResponse<byte[]> media(@PathVariable long messageId) {
        var mediaId = db.inTx(c -> one(c, "SELECT media_id FROM wa_messages WHERE id = ? AND media_id IS NOT NULL", messageId));
        if (mediaId == null) return HttpResponse.notFound();
        WhatsAppGraph.Media m;
        try {
            m = wa.media(mediaId);
        } catch (WhatsAppGraph.GraphException e) {
            throw Problems.conflict("media-unavailable", "WhatsApp no longer has this file (Meta keeps media for about 30 days)");
        }
        return HttpResponse.ok(m.bytes()).contentType(m.mime())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox");
    }

    /* ------------------------------------------------------------------ */

    private String send(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (WhatsAppGraph.GraphException e) {
            var hint = e.code != null && e.code == 131047 ? "the 24-hour window has closed: send the follow-up template"
                    : e.code != null && e.code == 131026 ? "this number cannot receive WhatsApp messages"
                    : e.getMessage();
            throw Problems.conflict("whatsapp-refused", "WhatsApp did not send it: " + hint);
        }
    }

    private boolean windowOpen(Connection c, UUID id) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT wa_window_until > ? FROM profiles WHERE identity_id = ?",
                clock.instant().atOffset(java.time.ZoneOffset.UTC), id); var rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** The customer's WhatsApp number, 91XXXXXXXXXX without a plus. */
    private static String recipient(Connection c, UUID id) throws SQLException {
        var to = one(c, """
                SELECT value FROM identity_keys WHERE identity_id = ? AND kind IN ('wa_id', 'phone')
                 ORDER BY kind = 'wa_id' DESC LIMIT 1""", id);
        if (to == null) throw Problems.conflict("no-number", "this customer has no WhatsApp number on file");
        return to.replaceAll("[^0-9]", "");
    }

    private static Object firstName(Connection c, UUID id) throws SQLException {
        var n = one(c, """
                SELECT COALESCE(NULLIF(attrs->>'first_name', ''), NULLIF(split_part(attrs->>'wa_name', ' ', 1), ''))
                  FROM profiles WHERE identity_id = ?""", id);
        return n == null ? "there" : n;
    }

    private static Map<String, Object> message(Connection c, String wamid) throws SQLException {
        var rows = Rows.list(c, """
                SELECT m.id, m.direction, m.kind, m.body, false AS has_media, m.template_key, false AS automated,
                       o.email AS sent_by, m.status, m.failed_reason, m.at
                  FROM wa_messages m LEFT JOIN operators o ON o.id = m.sent_by WHERE m.wamid = ?""", wamid);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private static String one(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such conversation");
        }
    }
}
