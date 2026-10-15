package in.brand.engage.ingest.ses;

import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SES events from SNS ({@code source = 'ses'}, P7-T01), matched to our send
 * by SES's message id, else to the person by the email address.
 *
 * <ul>
 * <li><b>Hard bounce</b> (Permanent): a permanent email suppression; the send fails.</li>
 * <li><b>Soft bounce</b> (Transient, Undetermined): nothing, until the third in
 *     7 days, which suppresses email for 30 days.</li>
 * <li><b>Complaint</b> ("this is spam"): a permanent suppression <i>and</i>
 *     email marketing consent withdrawn. Complaints are what get a sending
 *     domain blocked.</li>
 * <li><b>Delivery</b>: the send is delivered.</li>
 * </ul>
 */
@Singleton
public class SesInboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SesInboxHandler.class);
    static final int SOFT_BOUNCES = 3;

    private final ConsentWriter consents;
    private final EventWriter events;

    public SesInboxHandler(ConsentWriter consents, EventWriter events) {
        this.consents = consents;
        this.events = events;
    }

    @Override
    public String source() {
        return "ses";
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        String type;
        String bounceType;
        String messageId;
        String recipient;
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE(payload->>'eventType', payload->>'notificationType') AS type,
                       payload#>>'{bounce,bounceType}' AS bounce_type,
                       payload#>>'{mail,messageId}' AS message_id,
                       lower(COALESCE(payload#>>'{bounce,bouncedRecipients,0,emailAddress}',
                                      payload#>>'{complaint,complainedRecipients,0,emailAddress}',
                                      payload#>>'{mail,destination,0}')) AS recipient
                  FROM webhook_inbox WHERE source = 'ses' AND delivery_id = ?""", item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) return;
            type = rs.getString("type");
            bounceType = rs.getString("bounce_type");
            messageId = rs.getString("message_id");
            recipient = rs.getString("recipient");
        }
        Long sendId = null;
        UUID identity = null;
        try (var ps = Sql.prepare(c, "SELECT id, identity_id FROM sends WHERE provider_id = ? AND channel = 'email'",
                messageId == null ? "" : messageId); var rs = ps.executeQuery()) {
            if (rs.next()) {
                sendId = rs.getLong(1);
                identity = rs.getObject(2, UUID.class);
            }
        }
        if (identity == null && recipient != null) {
            try (var ps = Sql.prepare(c, "SELECT identity_id FROM identity_keys WHERE kind = 'email' AND value = ? LIMIT 1", recipient);
                 var rs = ps.executeQuery()) {
                if (rs.next()) identity = rs.getObject(1, UUID.class);
            }
        }
        if (identity == null) {
            LOG.info("SES {} for an address we do not know; ignored", type);
            return;
        }
        var ref = "ses:" + item.deliveryId();
        switch (type == null ? "" : type) {
            case "Bounce" -> {
                if ("Permanent".equals(bounceType)) {
                    suppress(c, identity, "hard_bounce", null);
                    if (sendId != null) fail(c, sendId, "ses_hard_bounce");
                } else {
                    events.write(c, identity, "email_soft_bounce", "ses", ref, Map.of("message_id", String.valueOf(messageId)));
                    try (var ps = Sql.prepare(c, """
                            SELECT count(*) FROM events WHERE identity_id = ? AND name = 'email_soft_bounce'
                               AND occurred_at > now() - interval '7 days'""", identity); var rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) >= SOFT_BOUNCES) suppress(c, identity, "soft_bounces", Instant.now().plusSeconds(30L * 86_400));
                    }
                }
            }
            case "Complaint" -> {
                suppress(c, identity, "complaint", null);
                consents.statement(c, identity, "withdrawn", "email_complaint", null, ref, Instant.now(),
                        List.<String[]>of(new String[] {"email", "marketing"}));
                events.write(c, identity, "email_complaint", "ses", ref, Map.of("message_id", String.valueOf(messageId)));
                LOG.warn("email complaint from identity {} (send {})", identity, sendId);
            }
            case "Delivery" -> {
                if (sendId != null) {
                    Sql.update(c, """
                            UPDATE sends SET delivered_at = COALESCE(delivered_at, now()),
                                   status = CASE WHEN status IN ('queued', 'sent') THEN 'delivered'::send_status ELSE status END
                             WHERE id = ?""", sendId);
                }
            }
            default -> LOG.debug("SES event {} ignored", type);
        }
    }

    private static void suppress(Connection c, UUID identity, String reason, Instant until) throws SQLException {
        Sql.update(c, """
                INSERT INTO suppressions (identity_id, channel, reason, until) VALUES (?, 'email', ?, ?)
                ON CONFLICT (identity_id, channel, reason) DO UPDATE SET until = EXCLUDED.until""",
                identity, reason, until == null ? null : until.atOffset(java.time.ZoneOffset.UTC));
    }

    private static void fail(Connection c, long sendId, String reason) throws SQLException {
        Sql.update(c, "UPDATE sends SET status = 'failed', failed_reason = ? WHERE id = ? AND status IN ('queued', 'sent', 'delivered')",
                reason, sendId);
    }
}
