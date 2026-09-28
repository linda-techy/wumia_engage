package in.brand.engage.policy;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** What has already been sent and spent: the inputs to caps and the budget guard. */
@Singleton
public class Usage {

    /**
     * Sends to this person on this channel and category since {@code since},
     * across every journey and campaign. Blocked and deferred rows are not
     * sends; failed ones count, because the provider may have delivered.
     * Served by {@code sends_cap_idx} (V1).
     */
    public long sendsSince(Connection c, UUID identityId, Channel channel, Category category, Instant since)
            throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM sends
                 WHERE identity_id = ? AND channel = CAST(? AS channel) AND category = CAST(? AS msg_category)
                   AND status NOT IN ('blocked', 'deferred') AND created_at > ?""",
                identityId, channel.dbName(), category.dbName(), since.atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Whether this template reached this person since {@code since} (blocked and deferred rows are not sends). */
    public boolean templateSentSince(Connection c, UUID identityId, Template template, Instant since)
            throws SQLException {
        // channel is redundant with the key but lets sends_cap_idx narrow the scan. Not
        // category: a WhatsApp reply is stored as SERVICE whatever the template's category.
        try (var ps = Sql.prepare(c, """
                SELECT EXISTS (SELECT 1 FROM sends
                 WHERE identity_id = ? AND channel = CAST(? AS channel)
                   AND template_key = ? AND status NOT IN ('blocked', 'deferred') AND created_at > ?)""",
                identityId, template.channel().dbName(), template.key(), since.atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Paise booked for this channel and category on an IST calendar day. */
    public long spentOn(Connection c, LocalDate istDay, Channel channel, Category category) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE(sum(paise), 0) FROM spend_ledger
                 WHERE day = CAST(? AS date) AND channel = CAST(? AS channel) AND category = CAST(? AS msg_category)""",
                istDay.toString(), channel.dbName(), category.dbName());
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
