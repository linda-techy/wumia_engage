package in.brand.engage.policy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Fixtures for the policy tests. Refuses to touch a non-_test database. */
@Singleton
public class PolicyTestData {

    private final Db db;

    public PolicyTestData(Db db) {
        this.db = db;
    }

    /** Back to defaults only: no config versions, no spend. Identities are fresh per test. */
    public void resetConfig() {
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to reset " + rs.getString(1));
            }
            try (var st = c.createStatement()) {
                // TRUNCATE, not DELETE: config_versions is append-only (V2 trigger).
                st.execute("TRUNCATE config_versions, spend_ledger CASCADE");
                st.execute("DELETE FROM config_keys WHERE key LIKE 'test.%'");
            }
            return null;
        });
    }

    /** Appends a config version, as the admin console would. */
    public void setConfig(String key, String selector, String jsonValue) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO config_versions (key, selector, value, changed_by, reason)
                VALUES (?, ?, ?::jsonb, ?, 'test')""", key, selector, jsonValue, operator(c)));
    }

    public UUID newIdentity() {
        var id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
        return id;
    }

    public void phone(UUID identityId, String msisdn) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO identity_keys (identity_id, kind, value, verified)
                VALUES (?, 'phone', ?, true)""", identityId, msisdn));
    }

    public void consent(UUID identityId, Channel channel, String purpose, String state, Instant occurredAt) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                VALUES (?, CAST(? AS channel), CAST(? AS purpose), CAST(? AS consent_state), 'test', ?)""",
                identityId, channel.dbName(), purpose, state, utc(occurredAt)));
    }

    public void grant(UUID identityId, Channel channel, String purpose, Instant occurredAt) {
        consent(identityId, channel, purpose, "granted", occurredAt);
    }

    public long device(UUID identityId, String platform, Instant lastRefreshedAt) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source,
                                         consent_copy_ver, last_refreshed_at)
                    VALUES (?, ?, ?, 'https://test.example', 'add_to_cart', 'push_v1', ?)
                    RETURNING id""",
                    identityId, "tok-" + UUID.randomUUID(), platform, utc(lastRefreshedAt));
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public void template(String key, Channel channel, Category category, String status) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO templates (key, channel, category, status)
                VALUES (?, CAST(? AS channel), CAST(? AS msg_category), ?)
                ON CONFLICT (key) DO UPDATE
                   SET channel = EXCLUDED.channel, category = EXCLUDED.category, status = EXCLUDED.status""",
                key, channel.dbName(), category.dbName(), status));
    }

    public void send(UUID identityId, Channel channel, Category category, String intentKey,
                     String status, Instant createdAt) {
        send(identityId, channel, category, "fixture", intentKey, status, createdAt);
    }

    public void send(UUID identityId, Channel channel, Category category, String templateKey, String intentKey,
                     String status, Instant createdAt) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO sends (identity_id, channel, category, template_key, intent_key,
                                   idempotency_key, status, created_at)
                VALUES (?, CAST(? AS channel), CAST(? AS msg_category), ?, ?, ?,
                        CAST(? AS send_status), ?)""",
                identityId, channel.dbName(), category.dbName(), templateKey, intentKey,
                "test:" + UUID.randomUUID(), status, utc(createdAt)));
    }

    public void spend(LocalDate day, Channel channel, Category category, long paise) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO spend_ledger (day, channel, category, messages, paise)
                VALUES (CAST(? AS date), CAST(? AS channel), CAST(? AS msg_category), 1, ?)""",
                day.toString(), channel.dbName(), category.dbName(), paise));
    }

    /** A config key with no default and no version: ConfigResolver must refuse it. */
    public void keyWithoutDefault(String key) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO config_keys (key, scope, value_type, label) VALUES (?, 'GLOBAL', 'INT', 'test')""",
                key));
    }

    public String holdoutBucket(UUID identityId, String experiment) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT bucket FROM holdouts WHERE identity_id = ? AND experiment = ?",
                    identityId, experiment);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    /** config_versions.changed_by must be a real operator row. */
    private static UUID operator(java.sql.Connection c) throws java.sql.SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO operators (email, full_name, password_hash, status)
                VALUES ('policy-test@example.com', 'Policy Test', 'x', 'active')
                ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name
                RETURNING id""");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
