package in.brand.engage.admin.audit;

import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Audit rows are written inside the caller's transaction, so a change and its
 * record commit together or not at all. An audit row that can be lost while
 * the change survives is not an audit trail.
 */
@Singleton
public class AuditLog {

    public void record(Connection c, UUID actorId, String action, String entityType, String entityId,
                       String beforeJson, String afterJson) throws SQLException {
        Sql.update(c, """
                INSERT INTO audit_log (actor_id, actor_kind, action, entity_type, entity_id, before, after)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                actorId, actorId == null ? "system" : "operator", action, entityType, entityId, beforeJson, afterJson);
    }
}
