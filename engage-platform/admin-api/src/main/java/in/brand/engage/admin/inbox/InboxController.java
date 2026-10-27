package in.brand.engage.admin.inbox;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
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
 * Staff messages are events the worker turns into sends (CLAUDE.md §1.1: one
 * door): policy checks the WhatsApp halt, the window and, for the template,
 * the opt-in; a refusal shows in the thread with its reason.
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
    private final EventWriter events;
    private final Clock clock;

    @jakarta.inject.Inject
    public InboxController(Db db, CurrentOperator current, PiiAccess pii, AuditLog audit, WhatsAppGraph wa, EventWriter events) {
        this(db, current, pii, audit, wa, events, Clock.systemUTC());
    }

    InboxController(Db db, CurrentOperator current, PiiAccess pii, AuditLog audit, WhatsAppGraph wa, EventWriter events, Clock clock) {
        this.db = db;
        this.current = current;
        this.pii = pii;
        this.audit = audit;
        this.wa = wa;
        this.events = events;
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
                    SELECT o.order_number, o.total_paise, o.created_at, o.cancelled_at IS NOT NULL AS cancelled,
                           s.status AS shipment_status, s.carrier, s.awb, s.tracking_url
                      FROM orders o
                      LEFT JOIN LATERAL (SELECT status, carrier, awb, tracking_url FROM shipments
                                          WHERE order_id = o.id ORDER BY status_at DESC LIMIT 1) s ON true
                     WHERE o.identity_id = ? ORDER BY o.created_at DESC LIMIT 5""", id));
            out.put("messages", Rows.list(c, """
                    SELECT * FROM (
                      SELECT m.id, m.direction, m.wamid, m.kind, m.body, m.media_id IS NOT NULL AS has_media, m.media_mime, m.media_name,
                             m.template_key, (m.direction = 'out' AND m.sent_by IS NULL) AS automated, o.email AS sent_by, m.status, m.failed_reason, m.at
                        FROM wa_messages m LEFT JOIN operators o ON o.id = m.sent_by
                       WHERE m.identity_id = ? ORDER BY m.at DESC LIMIT 300) x ORDER BY at""", id));
            out.put("windowOpen", windowOpen(c, id));
            out.put("followUps", Rows.list(c, """
                    SELECT DISTINCT w.key FROM wa_templates w JOIN templates t ON t.key = w.key
                     WHERE w.key = ANY(?) AND w.status = 'APPROVED' AND w.language = 'en'""", (Object) FOLLOW_UPS.toArray(String[]::new)));
            return out;
        });
    }

    /** Clears the unread count and shows the customer blue ticks on their latest message (best effort). */
    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/read")
    public HttpResponse<Void> read(@PathVariable String identityId) {
        var id = uuid(identityId);
        var latest = db.inTx(c -> {
            int unread = 0;
            try (var ps = Sql.prepare(c, "SELECT unread FROM wa_threads WHERE identity_id = ? FOR UPDATE", id); var rs = ps.executeQuery()) {
                if (rs.next()) unread = rs.getInt(1);
            }
            Sql.update(c, "UPDATE wa_threads SET unread = 0, updated_at = now() WHERE identity_id = ?", id);
            // Meta accepts read receipts for messages up to 30 days old.
            return unread == 0 ? null : one(c, """
                    SELECT wamid FROM wa_messages WHERE identity_id = ? AND direction = 'in' AND at > now() - interval '30 days'
                     ORDER BY at DESC LIMIT 1""", id);
        });
        if (latest != null && wa.configured()) {
            try {
                wa.markRead(latest);
            } catch (WhatsAppGraph.GraphException e) {
                // ticks are a courtesy; the inbox works without them
            }
        }
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

    /**
     * A free-form reply: only inside the 24 h the customer's last message opened.
     * Queued for the worker, which sends it within a second or two.
     */
    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/reply")
    public HttpResponse<Map<String, Object>> reply(@PathVariable String identityId, @Body ReplyBody body) {
        var id = uuid(identityId);
        var text = body == null || body.text() == null ? "" : body.text().strip();
        if (text.isEmpty() || text.length() > TEXT_MAX) throw Problems.badRequest("write a message of 1 to 4096 characters");
        var actor = current.id();
        db.inTx(c -> {
            if (!windowOpen(c, id)) {
                throw Problems.conflict("window-closed", "the 24-hour reply window has closed: send the follow-up template instead");
            }
            recipient(c, id);
            events.write(c, id, "inbox_staff_reply", "admin", "inbox_staff:" + UUID.randomUUID(),
                    Map.of("text", text, "operator_id", actor.toString()));
            Sql.update(c, "UPDATE wa_threads SET unread = 0, updated_at = now() WHERE identity_id = ?", id);
            pii.record(c, actor, id, "whatsapp_reply", "WhatsApp inbox reply");
            return null;
        });
        return HttpResponse.accepted().body(Map.of("status", "queued"));
    }

    /**
     * After the window: the approved utility follow-up, which re-opens the
     * conversation when the customer answers. Business-initiated, so the worker's
     * policy sends it only to a customer who opted in to WhatsApp.
     */
    @RequiresRole(Role.ANALYST)
    @Post("/{identityId}/template")
    public HttpResponse<Map<String, Object>> template(@PathVariable String identityId, @Body TemplateBody body) {
        var id = uuid(identityId);
        var key = body == null ? null : body.templateKey();
        if (key == null || !FOLLOW_UPS.contains(key)) throw Problems.badRequest("templateKey is one of " + FOLLOW_UPS);
        var actor = current.id();
        db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT 1 FROM wa_templates WHERE key = ? AND language = 'en' AND status = 'APPROVED' AND quality <> 'RED'""", key);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.conflict("template-not-approved", key + " is not approved by Meta yet");
            }
            recipient(c, id);
            events.write(c, id, "inbox_followup", "admin", "inbox_followup:" + UUID.randomUUID(),
                    Map.of("operator_id", actor.toString()));
            audit.record(c, actor, "inbox.template", "identity", id.toString(), null, "{\"template\":\"" + key + "\"}");
            return null;
        });
        return HttpResponse.accepted().body(Map.of("status", "queued"));
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
