package in.brand.engage.ingest.sms;

import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MSG91 delivery reports from the inbox ({@code source = 'msg91'}, normalised
 * by {@link Msg91Dlr}) onto the send they report (P4-T06).
 *
 * <ul>
 * <li>Matched by {@code sends.provider_id} = MSG91's request id; a report for
 *     a send we do not have is ignored.</li>
 * <li>{@code DELIVERED} (status 1): {@code sends.delivered_at}, and an
 *     {@code sms_delivered} event, which the worker turns into the cascade's
 *     {@code delivered} signal (CLAUDE.md §3.3: an SMS has no read receipt).</li>
 * <li>Any other final status (failed, rejected, NDNC, blocked): the send is
 *     failed with {@code msg91_<desc>}, and an {@code sms_failed} event. A DLT
 *     failure reason is logged as an error: content that does not match the
 *     registered template is the most common SMS failure, and only an
 *     operator can fix it.</li>
 * </ul>
 */
@Singleton
public class Msg91InboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Msg91InboxHandler.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MSG91_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.S]");
    /** Not final yet: nothing to record. */
    static final Set<String> INTERIM = Set.of("PENDING", "SUBMITTED", "SENT", "ACCEPTED", "QUEUED");

    private final EventWriter events;

    public Msg91InboxHandler(EventWriter events) {
        this.events = events;
    }

    @Override
    public String source() {
        return "msg91";
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT r->>'request_id' AS request_id, r->>'status' AS status, r->>'desc' AS descr,
                       r->>'failed_reason' AS failed_reason, r->>'date' AS at
                  FROM webhook_inbox, jsonb_array_elements(payload->'reports') r
                 WHERE source = 'msg91' AND delivery_id = ?""", item.deliveryId());
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                apply(c, rs.getString("request_id"), rs.getString("status"), rs.getString("descr"),
                        rs.getString("failed_reason"), rs.getString("at"));
            }
        }
    }

    private void apply(Connection c, String requestId, String status, String desc, String failedReason, String at)
            throws SQLException {
        if (requestId == null) return;
        long sendId;
        UUID identity;
        Long runId;
        try (var ps = Sql.prepare(c, "SELECT id, identity_id, cascade_run_id FROM sends WHERE provider_id = ? AND channel = 'sms'",
                requestId); var rs = ps.executeQuery()) {
            if (!rs.next()) {
                LOG.info("MSG91 report for request {}, which is not one of our sends; ignored", requestId);
                return;
            }
            sendId = rs.getLong(1);
            identity = rs.getObject(2, UUID.class);
            runId = Sql.nullableLong(rs, "cascade_run_id");
        }
        var label = desc == null ? "" : desc.toUpperCase(Locale.ROOT);
        if (INTERIM.contains(label)) return;

        Map<String, Object> props = new HashMap<>();
        props.put("send_id", sendId);
        if (runId != null) props.put("run_id", runId);
        if ("1".equals(status) || label.equals("DELIVERED")) {
            Sql.update(c, """
                    UPDATE sends SET delivered_at = COALESCE(delivered_at, ?),
                           status = CASE WHEN status IN ('queued', 'sent') THEN 'delivered'::send_status ELSE status END
                     WHERE id = ?""", time(at), sendId);
            events.write(c, identity, "sms_delivered", "msg91", "sms_delivered:" + sendId, props);
            return;
        }
        var reason = "msg91_" + (label.isEmpty() ? "status_" + status : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"));
        Sql.update(c, "UPDATE sends SET status = 'failed', failed_reason = ? WHERE id = ? AND status IN ('queued', 'sent')",
                reason, sendId);
        if (failedReason != null && failedReason.toUpperCase(Locale.ROOT).contains("DLT")) {
            LOG.error("SMS send {} failed on DLT: '{}'. The template text or its variables do not match the DLT registration.",
                    sendId, failedReason);
        } else {
            LOG.warn("SMS send {} not delivered: {} {}", sendId, label, failedReason == null ? "" : failedReason);
        }
        props.put("reason", reason);
        events.write(c, identity, "sms_failed", "msg91", "sms_failed:" + sendId, props);
    }

    /** MSG91 report times are IST wall-clock; unreadable → now. */
    static OffsetDateTime time(String at) {
        if (at != null) {
            try {
                return LocalDateTime.parse(at, MSG91_TIME).atZone(IST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
