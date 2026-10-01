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

    /** Runs {@code sql} only on a database whose name ends in {@code _test}. */
    private void onTestDb(String sql) {
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
                st.execute(sql);
            }
            return null;
        });
    }

    public void truncateInbox() {
        onTestDb("TRUNCATE webhook_inbox");
    }

    public void insertInboxRow(String source, String deliveryId, String topic, boolean processed, String lastError) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO webhook_inbox (source, delivery_id, topic, payload, processed_at, last_error, attempts)
                VALUES (?, ?, ?, '{}'::jsonb, CASE WHEN ? THEN now() END, ?,
                        CASE WHEN CAST(? AS text) IS NULL THEN 0 ELSE 3 END)""",
                source, deliveryId, topic, processed, lastError, lastError));
    }

    public void truncateCustomerTables() {
        onTestDb("""
                TRUNCATE identities, identity_keys, profiles, consents, orders, checkouts, payment_attempts,
                         devices, shipments, shipment_events CASCADE""");
    }

    /** An identity with an email and a phone key (normalised 91XXXXXXXXXX). */
    public String createIdentity(String email, String phone) {
        return db.inTx(c -> {
            var id = UUID.randomUUID();
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'email', ?, true)", id, email);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'phone', ?, false)", id, phone);
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Mary\",\"city\":\"Kochi\"}')", id);
            return id.toString();
        });
    }

    public String loginAsViewer(io.micronaut.http.client.HttpClient client) {
        return loginAs(client, "viewer@example.com", "VIEWER");
    }

    public String loginAsAnalyst(io.micronaut.http.client.HttpClient client) {
        return loginAs(client, "analyst@example.com", "ANALYST");
    }

    /** Creates the operator if absent (password "hunter2hunter2") and returns an access token. */
    public String loginAs(io.micronaut.http.client.HttpClient client, String email, String role) {
        boolean exists = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM operators WHERE email = ?", email); var rs = ps.executeQuery()) {
                return rs.next();
            }
        });
        if (!exists) createOperator(email, "hunter2hunter2", role);
        var body = client.toBlocking().retrieve(io.micronaut.http.HttpRequest.POST("/api/auth/login",
                java.util.Map.of("email", email, "password", "hunter2hunter2")), java.util.Map.class);
        return (String) body.get("accessToken");
    }

    public void insertFailedPayment(String identityId, String paymentId, long amountPaise, String method,
                                    String errorSource, String errorReason, String matchMethod, String checkoutToken) {
        db.inTx(c -> {
            if (checkoutToken != null) {
                Sql.update(c, "INSERT INTO checkouts (token, identity_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        checkoutToken, UUID.fromString(identityId));
            }
            return Sql.update(c, """
                    INSERT INTO payment_attempts (gateway_payment_id, status, amount_paise, currency, method, phone,
                                                  error_source, error_reason, identity_id, checkout_token, match_method,
                                                  gateway_created_at, received_at)
                    VALUES (?, 'failed', ?, 'INR', ?, '918606572870', ?, ?, ?, ?, ?, now() - interval '3 seconds', now())""",
                    paymentId, amountPaise, method, errorSource, errorReason, UUID.fromString(identityId),
                    checkoutToken, matchMethod);
        });
    }

    public void deleteConsentCopy(String version) {
        db.inTx(c -> {
            Sql.update(c, "DELETE FROM consents WHERE copy_version = ?", version);
            return Sql.update(c, "DELETE FROM consent_copy_versions WHERE version = ?", version);
        });
    }

    public String loginAsConfigAdmin(io.micronaut.http.client.HttpClient client) {
        return loginWithMfa(client, "config@example.com", "CONFIG_ADMIN");
    }

    public String loginAsCampaignEditor(io.micronaut.http.client.HttpClient client) {
        return loginWithMfa(client, "campaigns@example.com", "CAMPAIGN_EDIT");
    }

    /** A role above ANALYST: enrol a TOTP secret, then password + code. */
    public String loginWithMfa(io.micronaut.http.client.HttpClient client, String email, String role) {
        var id = createOperator(email, "hunter2hunter2", role);
        var secret = in.brand.engage.core.crypto.Totp.generateSecret();
        var key = in.brand.engage.core.crypto.SecretBox.keyFromHex("0123456789abcdef".repeat(4));
        enrolMfa(id, in.brand.engage.core.crypto.SecretBox.encrypt(key, secret));
        var first = client.toBlocking().retrieve(io.micronaut.http.HttpRequest.POST("/api/auth/login",
                java.util.Map.of("email", email, "password", "hunter2hunter2")), java.util.Map.class);
        var second = client.toBlocking().retrieve(io.micronaut.http.HttpRequest.POST("/api/auth/mfa",
                java.util.Map.of("mfaToken", first.get("mfaToken"),
                        "code", in.brand.engage.core.crypto.Totp.code(secret, java.time.Instant.now()))),
                java.util.Map.class);
        return (String) second.get("accessToken");
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
