package in.brand.engage.persistence;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The {@code devices} table (V3): FCM tokens and their lifecycle
 * (phase-2 §9). Every write to it goes through here: storefront registration
 * and refresh (ingest-api), and pruning on provider errors (channels).
 *
 * <p>{@code active = false} always carries a reason (a CHECK enforces it):
 * rotated | unregistered | sender_mismatch | invalid_token | dormant | user_off.
 */
@Singleton
public class DeviceRepository {

    /**
     * Registers a token, or re-activates it. The token is unique, so a browser
     * that re-subscribes gets its old row back rather than a duplicate.
     */
    public long upsert(Connection c, UUID identityId, String token, String platform, String browser,
                       String origin, String permissionSource, String copyVersion) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, permission_source, consent_copy_ver)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fcm_token) DO UPDATE
                   SET identity_id = EXCLUDED.identity_id, platform = EXCLUDED.platform,
                       browser = COALESCE(EXCLUDED.browser, devices.browser),
                       permission_source = EXCLUDED.permission_source,
                       consent_copy_ver = EXCLUDED.consent_copy_ver,
                       active = true, deactivated_reason = NULL, last_refreshed_at = now()
                RETURNING id""",
                identityId, token, platform, browser, origin, permissionSource, copyVersion);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Token rotation: {@code token} takes over {@code previous}'s consent
     * context and {@code previous} is deactivated as {@code rotated}.
     *
     * @return false when {@code previous} is not this identity's device
     */
    public boolean rotate(Connection c, UUID identityId, String previous, String token) throws SQLException {
        int n = Sql.update(c, """
                INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, sw_version,
                                     permission_source, consent_copy_ver)
                SELECT identity_id, ?, platform, browser, origin, sw_version, permission_source, consent_copy_ver
                  FROM devices WHERE fcm_token = ? AND identity_id = ?
                ON CONFLICT (fcm_token) DO UPDATE
                   SET active = true, deactivated_reason = NULL, last_refreshed_at = now()
                 WHERE devices.identity_id = EXCLUDED.identity_id""",
                token, previous, identityId);
        if (n == 0) return false;
        Sql.update(c, """
                UPDATE devices SET active = false, deactivated_reason = 'rotated'
                 WHERE fcm_token = ? AND identity_id = ? AND active""", previous, identityId);
        return true;
    }

    /** Weekly client refresh with an unchanged token. @return false if not this identity's live device */
    public boolean touch(Connection c, UUID identityId, String token) throws SQLException {
        return Sql.update(c, """
                UPDATE devices SET last_refreshed_at = now()
                 WHERE fcm_token = ? AND identity_id = ? AND active""", token, identityId) > 0;
    }

    /** The shopper's own device, by token (e.g. "Turn off" in-page). */
    public void deactivate(Connection c, UUID identityId, String token, String reason) throws SQLException {
        Sql.update(c, """
                UPDATE devices SET active = false, deactivated_reason = ?
                 WHERE fcm_token = ? AND identity_id = ? AND active""", reason, token, identityId);
    }

    /** Pruning on a provider error for one token (FCM UNREGISTERED and similar). */
    public void deactivate(Connection c, long deviceId, String reason) throws SQLException {
        Sql.update(c, "UPDATE devices SET active = false, deactivated_reason = ? WHERE id = ? AND active",
                reason, deviceId);
    }
}
