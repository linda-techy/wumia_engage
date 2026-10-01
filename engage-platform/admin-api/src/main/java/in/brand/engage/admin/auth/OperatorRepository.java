package in.brand.engage.admin.auth;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Operators and their roles. Login throttling lives here, next to the counters. */
@Singleton
public class OperatorRepository {

    /** 5 failures, then 15 minutes. Slow enough to stop guessing, short enough to be usable. */
    private static final int MAX_FAILURES = 5;
    private static final String LOCK_INTERVAL = "15 minutes";

    public record Operator(UUID id, String email, String fullName, String passwordHash, byte[] mfaSecretEnc,
                           boolean mfaEnrolled, String status, int failedLogins, OffsetDateTime lockedUntil,
                           int ver, List<String> roles) {

        public boolean locked() {
            return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
        }

        public boolean active() {
            return "active".equals(status);
        }
    }

    private final Db db;

    public OperatorRepository(Db db) {
        this.db = db;
    }

    public Optional<Operator> findByEmail(String email) {
        return findBy("operator_by_email.sql", email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    public Optional<Operator> findById(UUID id) {
        return findBy("operator_by_id.sql", id);
    }

    private Optional<Operator> findBy(String sqlFile, Object param) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, SqlFiles.get(sqlFile), param); var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.<Operator>empty();
            }
        });
    }

    private static Operator map(ResultSet rs) throws SQLException {
        var roles = (String[]) rs.getArray("roles").getArray();
        var changed = Sql.timestamp(rs, "password_changed_at");
        return new Operator(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getBytes("mfa_secret_enc"),
                rs.getBoolean("mfa_enrolled"),
                rs.getString("status"),
                rs.getInt("failed_logins"),
                Sql.timestamp(rs, "locked_until"),
                // A password change moves this, which invalidates outstanding access tokens.
                (int) (changed.toEpochSecond() % Integer.MAX_VALUE),
                List.of(roles));
    }

    /** Roles for an operator whose id is already known, without re-fetching the whole row. */
    public List<String> roles(UUID operatorId) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, SqlFiles.get("operator_roles.sql"), operatorId);
                 var rs = ps.executeQuery()) {
                var result = new ArrayList<String>();
                while (rs.next()) result.add(rs.getString("role"));
                return result;
            }
        });
    }

    public void recordFailedLogin(UUID operatorId) {
        db.inTx(c -> Sql.update(c, """
                UPDATE operators
                   SET failed_logins = failed_logins + 1,
                       locked_until = CASE WHEN failed_logins + 1 >= ?
                                           THEN now() + interval '%s' ELSE locked_until END,
                       updated_at = now()
                 WHERE id = ?""".formatted(LOCK_INTERVAL), MAX_FAILURES, operatorId));
    }

    public void clearFailures(UUID operatorId) {
        db.inTx(c -> Sql.update(c, """
                UPDATE operators SET failed_logins = 0, locked_until = NULL,
                       last_login_at = now(), updated_at = now()
                 WHERE id = ?""", operatorId));
    }

    public void setPassword(UUID operatorId, String passwordHash) {
        db.inTx(c -> {
            setPassword(c, operatorId, passwordHash);
            return null;
        });
    }

    /**
     * Same statement, on a connection the caller already holds open. Login and
     * set-password flows must write their audit_log row in the same
     * transaction as the change; a repository that always opens its own
     * transaction would force that into two separate commits.
     */
    public void setPassword(Connection c, UUID operatorId, String passwordHash) throws SQLException {
        Sql.update(c, """
                UPDATE operators
                   -- ver is the epoch second of this column: it must move even when two
                   -- changes land in the same second, or old tokens and links survive.
                   SET password_hash = ?,
                       password_changed_at = GREATEST(now(), password_changed_at + interval '1 second'),
                       status = 'active',
                       failed_logins = 0, locked_until = NULL, updated_at = now()
                 WHERE id = ?""", passwordHash, operatorId);
    }

    /**
     * Stores an MFA secret before the operator has proven possession of it.
     * Enrollment (mfa_enrolled_at) is not set here — a secret an operator never
     * confirmed with a valid code must not count as MFA being active.
     */
    public void stageMfaSecret(UUID operatorId, byte[] encrypted) {
        db.inTx(c -> {
            stageMfaSecret(c, operatorId, encrypted);
            return null;
        });
    }

    public void stageMfaSecret(Connection c, UUID operatorId, byte[] encrypted) throws SQLException {
        Sql.update(c, "UPDATE operators SET mfa_secret_enc = ?, updated_at = now() WHERE id = ?",
                encrypted, operatorId);
    }

    public void setMfaSecret(UUID operatorId, byte[] encrypted) {
        db.inTx(c -> {
            setMfaSecret(c, operatorId, encrypted);
            return null;
        });
    }

    public void setMfaSecret(Connection c, UUID operatorId, byte[] encrypted) throws SQLException {
        Sql.update(c, """
                UPDATE operators SET mfa_secret_enc = ?, mfa_enrolled_at = now(), updated_at = now()
                 WHERE id = ?""", encrypted, operatorId);
    }

    public boolean isEmpty() {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT NOT EXISTS (SELECT 1 FROM operators)"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        });
    }

    public UUID createInvitedOwner(String email, String fullName, String placeholderHash) {
        return db.inTx(c -> createInvitedOwner(c, email, fullName, placeholderHash));
    }

    public UUID createInvitedOwner(Connection c, String email, String fullName, String placeholderHash)
            throws SQLException {
        var id = UUID.randomUUID();
        Sql.update(c, """
                INSERT INTO operators (id, email, full_name, password_hash, status)
                VALUES (?, ?, ?, ?, 'invited')""",
                id, email.toLowerCase(Locale.ROOT), fullName, placeholderHash);
        Sql.update(c, "INSERT INTO operator_roles (operator_id, role) VALUES (?, 'OWNER')", id);
        return id;
    }
}
