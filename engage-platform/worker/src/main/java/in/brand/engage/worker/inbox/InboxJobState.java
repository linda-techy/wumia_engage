package in.brand.engage.worker.inbox;

import in.brand.engage.persistence.Sql;
import java.sql.Connection;
import java.sql.SQLException;

/** How far an inbox job has read wa_messages (V50). The row lock keeps two workers from doing the same pass. */
final class InboxJobState {

    private InboxJobState() {}

    static long lock(Connection c, String job) throws SQLException {
        Sql.update(c, "INSERT INTO inbox_job_state (job, last_message_id) VALUES (?, 0) ON CONFLICT (job) DO NOTHING", job);
        try (var ps = Sql.prepare(c, "SELECT last_message_id FROM inbox_job_state WHERE job = ? FOR UPDATE", job);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void advance(Connection c, String job, long lastMessageId) throws SQLException {
        Sql.update(c, "UPDATE inbox_job_state SET last_message_id = GREATEST(last_message_id, ?), updated_at = now() WHERE job = ?",
                lastMessageId, job);
    }
}
