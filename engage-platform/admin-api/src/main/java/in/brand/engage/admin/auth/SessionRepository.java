package in.brand.engage.admin.auth;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh tokens: opaque, 14 days, rotated on every use, stored as sha256.
 * Presenting an already-rotated token means it was captured and replayed. We
 * cannot tell attacker from victim, so the whole family dies and both
 * re-authenticate. That turns a stolen token from a permanent backdoor into a
 * 15-minute window plus an alert.
 */
@Singleton
public class SessionRepository {

    private static final java.time.Duration REFRESH_TTL = java.time.Duration.ofDays(14);

    public record Issued(UUID sessionId, UUID familyId, UUID operatorId, String refreshToken) {}

    public record Session(UUID id, UUID operatorId, UUID familyId, OffsetDateTime issuedAt,
                          OffsetDateTime expiresAt, OffsetDateTime rotatedAt, OffsetDateTime revokedAt,
                          String revokedReason) {

        public boolean revoked() {
            return revokedAt != null;
        }
    }

    public static final class AuthFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public AuthFailure(String message) { super(message); }
    }

    /** Outcome of a `FOR UPDATE` lookup, returned so the transaction can commit
     * a reuse revocation before the caller ever sees the failure. */
    private enum Status { OK, UNKNOWN, REUSED, DEAD }

    private record Lookup(Status status, UUID operatorId, UUID familyId) {}

    private final Db db;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public SessionRepository(Db db, AuditLog audit) {
        this.db = db;
        this.audit = audit;
    }

    public Issued issue(UUID operatorId, UUID familyId, String userAgent) {
        return db.inTx(c -> issue(c, operatorId, familyId, userAgent));
    }

    /** In the caller's transaction, so a login and its audit row commit together. */
    public Issued issue(Connection c, UUID operatorId, UUID familyId, String userAgent) throws SQLException {
        var raw = new byte[32];
        random.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var sessionId = UUID.randomUUID();
        Sql.update(c, """
                INSERT INTO operator_sessions (id, operator_id, family_id, refresh_hash, user_agent, expires_at)
                VALUES (?, ?, ?, ?, ?, now() + interval '%d days')""".formatted(REFRESH_TTL.toDays()),
                sessionId, operatorId, familyId, sha256(token), userAgent);
        return new Issued(sessionId, familyId, operatorId, token);
    }

    /**
     * Whether an access token's session may still be used: not revoked and not
     * expired. A rotated session stays usable until its access token expires
     * (15 min); revocation (logout, reuse, password change) ends it at once.
     */
    public boolean isLive(UUID sessionId) {
        return sessionById(sessionId)
                .map(s -> !s.revoked() && s.expiresAt().isAfter(OffsetDateTime.now()))
                .orElse(false);
    }

    /** Which family and operator a refresh token belongs to. */
    public record SessionRef(UUID familyId, UUID operatorId) {}

    /** The family and operator of a known refresh token (rotated or not). */
    public Optional<SessionRef> refOf(String presented) {
        if (presented == null || presented.isBlank()) return Optional.empty();
        var hash = sha256(presented);
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c,
                    "SELECT family_id, operator_id FROM operator_sessions WHERE refresh_hash = ?", hash);
                 var rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new SessionRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                        : Optional.<SessionRef>empty();
            }
        });
    }

    /** Every live session of one operator (a password was set or changed). */
    public void revokeAllFor(Connection c, UUID operatorId, String reason) throws SQLException {
        Sql.update(c, """
                UPDATE operator_sessions SET revoked_at = now(), revoked_reason = ?
                 WHERE operator_id = ? AND revoked_at IS NULL""", reason, operatorId);
    }

    /** In the caller's transaction (logout writes its audit row with it). */
    public void revokeFamily(Connection c, UUID familyId, String reason) throws SQLException {
        revokeFamilyIn(c, familyId, reason);
    }

    public Issued rotate(String presented) {
        var hash = sha256(presented == null ? "" : presented);
        // The whole detect-and-revoke decision happens inside one FOR UPDATE
        // transaction: two concurrent rotations of the same token must not both
        // see "not yet rotated" and both issue a successor.
        var found = db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT id, operator_id, family_id, rotated_at, revoked_at, expires_at
                      FROM operator_sessions WHERE refresh_hash = ? FOR UPDATE""", hash);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return new Lookup(Status.UNKNOWN, null, null);

                var sessionId = rs.getObject("id", UUID.class);
                var operatorId = rs.getObject("operator_id", UUID.class);
                var familyId = rs.getObject("family_id", UUID.class);
                boolean rotated = rs.getObject("rotated_at") != null;
                boolean revoked = rs.getObject("revoked_at") != null;
                boolean expired = Sql.timestamp(rs, "expires_at").isBefore(OffsetDateTime.now());

                if (rotated) {
                    // Revocation and audit row commit with this transaction even
                    // though the caller ultimately gets AuthFailure: the record of
                    // what happened must survive regardless of the outcome we report.
                    revokeFamilyIn(c, familyId, "refresh_reuse_detected");
                    audit.record(c, operatorId, "auth.refresh_reuse", "operator_session",
                            sessionId.toString(), null, "{\"family\":\"" + familyId + "\"}");
                    return new Lookup(Status.REUSED, null, null);
                }
                if (revoked || expired) return new Lookup(Status.DEAD, null, null);

                Sql.update(c, "UPDATE operator_sessions SET rotated_at = now() WHERE id = ?", sessionId);
                return new Lookup(Status.OK, operatorId, familyId);
            }
        });

        return switch (found.status()) {
            case UNKNOWN -> throw new AuthFailure("unknown refresh token");
            case REUSED -> throw new AuthFailure("refresh token already used");
            case DEAD -> throw new AuthFailure("refresh token no longer valid");
            case OK -> issue(found.operatorId(), found.familyId(), null);
        };
    }

    public void revokeFamily(UUID familyId, String reason) {
        db.inTx(c -> {
            revokeFamilyIn(c, familyId, reason);
            return null;
        });
    }

    private void revokeFamilyIn(Connection c, UUID familyId, String reason) throws SQLException {
        Sql.update(c, """
                UPDATE operator_sessions SET revoked_at = now(), revoked_reason = ?
                 WHERE family_id = ? AND revoked_at IS NULL""", reason, familyId);
    }

    public Optional<Session> sessionById(UUID sessionId) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT id, operator_id, family_id, issued_at, expires_at, rotated_at, revoked_at, revoked_reason
                      FROM operator_sessions WHERE id = ?""", sessionId);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.<Session>empty();
                return Optional.of(new Session(
                        rs.getObject("id", UUID.class),
                        rs.getObject("operator_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        Sql.timestamp(rs, "issued_at"),
                        Sql.timestamp(rs, "expires_at"),
                        Sql.timestamp(rs, "rotated_at"),
                        Sql.timestamp(rs, "revoked_at"),
                        rs.getString("revoked_reason")));
            }
        });
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
