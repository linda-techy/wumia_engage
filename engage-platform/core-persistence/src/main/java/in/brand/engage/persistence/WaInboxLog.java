package in.brand.engage.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The WhatsApp inbox's record (V49 {@code wa_messages}, {@code wa_threads}),
 * written by whoever sees a message first: ingest-api for what customers send
 * and for delivery statuses, the router for Engage's automated messages, and
 * admin-api for staff replies. Every write is idempotent on the WhatsApp
 * message id, so a replayed webhook changes nothing.
 */
public final class WaInboxLog {

    /** What the thread list shows of a message. */
    static final int PREVIEW_MAX = 120;

    private WaInboxLog() {}

    /** A message from a customer. @return false when it was already recorded */
    public static boolean inbound(Connection c, UUID identity, String wamid, String kind, String body, String mediaId,
                                  String mediaMime, String mediaName, String contextWamid, Instant at) throws SQLException {
        int fresh = Sql.update(c, """
                INSERT INTO wa_messages (identity_id, direction, wamid, kind, body, media_id, media_mime, media_name, context_wamid, at)
                VALUES (?, 'in', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (wamid) DO NOTHING""", identity, wamid, kind, body, mediaId, mediaMime, mediaName, contextWamid, utc(at));
        if (fresh == 0) return false;
        Sql.update(c, """
                INSERT INTO wa_threads (identity_id, last_message_at, last_inbound_at, last_preview, unread, status)
                VALUES (?, ?, ?, ?, 1, 'open')
                ON CONFLICT (identity_id) DO UPDATE
                   SET last_preview = CASE WHEN EXCLUDED.last_message_at >= wa_threads.last_message_at
                                           THEN EXCLUDED.last_preview ELSE wa_threads.last_preview END,
                       last_message_at = GREATEST(wa_threads.last_message_at, EXCLUDED.last_message_at),
                       last_inbound_at = GREATEST(wa_threads.last_inbound_at, EXCLUDED.last_inbound_at),
                       unread = wa_threads.unread + 1, status = 'open', updated_at = now()""",
                identity, utc(at), utc(at), preview(kind, body));
        return true;
    }

    /**
     * A message Engage sent: an automated one ({@code sendId}) or a staff reply
     * ({@code sentBy}). Does not touch the unread count or reopen a done thread.
     */
    public static void outbound(Connection c, UUID identity, String wamid, String kind, String body, String templateKey,
                                Long sendId, UUID sentBy, Instant at) throws SQLException {
        int fresh = Sql.update(c, """
                INSERT INTO wa_messages (identity_id, direction, wamid, kind, body, template_key, send_id, sent_by, status, at)
                VALUES (?, 'out', ?, ?, ?, ?, ?, ?, 'sent', ?)
                ON CONFLICT (wamid) DO NOTHING""", identity, wamid, kind, body, templateKey, sendId, sentBy, utc(at));
        if (fresh == 0) return;
        Sql.update(c, """
                INSERT INTO wa_threads (identity_id, last_message_at, last_preview, unread, status)
                VALUES (?, ?, ?, 0, 'done')
                ON CONFLICT (identity_id) DO UPDATE
                   SET last_preview = CASE WHEN EXCLUDED.last_message_at >= wa_threads.last_message_at
                                           THEN EXCLUDED.last_preview ELSE wa_threads.last_preview END,
                       last_message_at = GREATEST(wa_threads.last_message_at, EXCLUDED.last_message_at),
                       updated_at = now()""",
                identity, utc(at), preview(kind, body));
    }

    /** Meta's delivery status for a message Engage sent; never moves backwards (read stays read). */
    public static void status(Connection c, String wamid, String status, String failedReason) throws SQLException {
        if (wamid == null || status == null) return;
        Sql.update(c, """
                UPDATE wa_messages SET status = ?, failed_reason = COALESCE(?, failed_reason)
                 WHERE wamid = ? AND direction = 'out'
                   AND (status IS NULL
                        OR array_position(ARRAY['sent','delivered','read'], status) < array_position(ARRAY['sent','delivered','read'], ?)
                        OR ? = 'failed')""",
                status, failedReason, wamid, status, status);
    }

    static String preview(String kind, String body) {
        var text = body != null && !body.isBlank() ? body.strip().replaceAll("\\s+", " ")
                : switch (kind == null ? "" : kind) {
                    case "image" -> "📷 Photo";
                    case "video" -> "🎥 Video";
                    case "audio" -> "🎤 Voice message";
                    case "document" -> "📄 Document";
                    case "sticker" -> "Sticker";
                    case "location" -> "📍 Location";
                    default -> "Message";
                };
        return text.length() > PREVIEW_MAX ? text.substring(0, PREVIEW_MAX - 1) + "…" : text;
    }

    private static java.time.OffsetDateTime utc(Instant at) {
        return (at == null ? Instant.now() : at).atOffset(ZoneOffset.UTC);
    }
}
