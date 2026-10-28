package in.brand.engage.admin.privacy;

import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Every look at a customer identifier by an operator: the journey inspector's
 * phone lookup, a per-field reveal, a PII export. Each writes
 * {@code pii_unmask_log} (who, whose, which field, why) and counts toward one
 * limit of {@value #DAILY_LIMIT} per operator per 24 h (07-api-contract): an
 * operator who needs more is exporting the list one record at a time.
 *
 * <p>A WhatsApp conversation the customer started (the inbox, V49) is not a
 * lookup: it is logged once per operator, customer and field a day, however
 * often the open chat refreshes, and does not count toward the limit (staff
 * answering more than 50 customers a day is support, not harvesting).
 */
@Singleton
public class PiiAccess {

    public static final int DAILY_LIMIT = 50;
    public static final int MIN_REASON = 5;
    /** Fields of customer-initiated conversations: audited, deduplicated per day, outside the lookup limit. */
    static final java.util.Set<String> CONVERSATION_FIELDS = java.util.Set.of("whatsapp_inbox", "whatsapp_reply");

    /** The reason, trimmed; 400 when missing or too long. */
    public static String reason(String reason) {
        var r = reason == null ? "" : reason.strip();
        if (r.length() < MIN_REASON) throw Problems.badRequest("say why you need this customer's details");
        if (r.length() > 500) throw Problems.badRequest("reason is at most 500 characters");
        return r;
    }

    /**
     * Checks the limit and logs one access, on the caller's transaction, so
     * the log row commits with whatever it records. Serialised per operator,
     * so two tabs cannot both take the last one.
     *
     * @param identityId null for a lookup that matched nobody, or an export
     */
    public void record(Connection c, UUID operatorId, UUID identityId, String field, String reason) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "unmask:" + operatorId);
             var rs = ps.executeQuery()) {
            rs.next();
        }
        if (CONVERSATION_FIELDS.contains(field)) {
            try (var ps = Sql.prepare(c, """
                    SELECT 1 FROM pii_unmask_log WHERE operator_id = ? AND identity_id = ? AND field = ?
                       AND at > now() - interval '24 hours'""", operatorId, identityId, field); var rs = ps.executeQuery()) {
                if (rs.next()) return;               // already in today's audit trail
            }
            Sql.update(c, "INSERT INTO pii_unmask_log (operator_id, identity_id, field, reason) VALUES (?, ?, ?, ?)",
                    operatorId, identityId, field, reason);
            return;
        }
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM pii_unmask_log WHERE operator_id = ? AND at > now() - interval '24 hours'
                   AND field NOT IN ('whatsapp_inbox', 'whatsapp_reply')""", operatorId);
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) >= DAILY_LIMIT) {
                throw new Problems.ApiException(HttpStatus.TOO_MANY_REQUESTS, "unmask-limit",
                        DAILY_LIMIT + " customer lookups in 24 hours is the limit; ask an OWNER if this is an incident");
            }
        }
        Sql.update(c, "INSERT INTO pii_unmask_log (operator_id, identity_id, field, reason) VALUES (?, ?, ?, ?)",
                operatorId, identityId, field, reason);
    }
}
