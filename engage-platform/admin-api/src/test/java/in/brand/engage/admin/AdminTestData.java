package in.brand.engage.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.brand.engage.admin.auth.PasswordHasher;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.security.MessageDigest;
import java.util.UUID;

/** Test fixtures for admin-api. Refuses to touch a non-_test database. */
@Singleton
public class AdminTestData {

    private final Db db;
    private final PasswordHasher hasher;

    public AdminTestData(Db db, PasswordHasher hasher) {
        this.db = db;
        this.hasher = hasher;
    }

    public void cleanAdminTables() {
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
                st.execute("TRUNCATE operator_sessions, operator_roles, audit_log, operators CASCADE");
            }
            return null;
        });
    }

    public UUID createOperator(String email, String password, String... roles) {
        return db.inTx(c -> {
            var id = UUID.randomUUID();
            Sql.update(c, """
                    INSERT INTO operators (id, email, full_name, password_hash, status)
                    VALUES (?, ?, ?, ?, 'active')""",
                    id, email.toLowerCase(java.util.Locale.ROOT), "Test Operator", hasher.hash(password.toCharArray()));
            for (var role : roles) {
                Sql.update(c, "INSERT INTO operator_roles (operator_id, role) VALUES (?, ?)", id, role);
            }
            return id;
        });
    }

    public void enrolMfa(UUID operatorId, byte[] encryptedSecret) {
        db.inTx(c -> Sql.update(c,
                "UPDATE operators SET mfa_secret_enc = ?, mfa_enrolled_at = now() WHERE id = ?",
                encryptedSecret, operatorId));
    }

    public long countSessionsWithRawToken(String token) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c,
                    "SELECT count(*) FROM operator_sessions WHERE refresh_hash = ?::bytea", token.getBytes());
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public long countAudit(String action) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM audit_log WHERE action = ?", action);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
