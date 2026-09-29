package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Fixtures for router and orchestrator tests. Refuses to touch a non-_test database. */
@Singleton
public class OrchestratorTestData {

    private final Db db;
    private final ConfigResolver config;

    public OrchestratorTestData(Db db, ConfigResolver config) {
        this.db = db;
        this.config = config;
    }

    /** Defaults only, except no global holdout (it would hold out 1 in 20 test identities); no live runs. */
    public void resetConfig() {
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to reset " + rs.getString(1));
            }
            try (var st = c.createStatement()) {
                st.execute("TRUNCATE config_versions CASCADE");
                // tick() claims every due run: none may be left over from another test.
                st.execute("UPDATE cascade_runs SET status = 'cancelled', outcome = 'test_reset' WHERE status IN ('active','waiting')");
            }
            Sql.update(c, """
                    INSERT INTO config_versions (key, selector, value, changed_by, reason)
                    VALUES ('holdout.global_pct', '*', '0', ?, 'test')""", operator(c));
            return null;
        });
        config.invalidate();
    }

    /** An identity with a fresh web push device and a push marketing grant. */
    public Subscriber subscriber() {
        var id = UUID.randomUUID();
        var token = "tok-" + UUID.randomUUID();
        long device = db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'push', 'marketing', 'granted', 'test', now() - interval '30 days')""", id);
            try (var ps = Sql.prepare(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1') RETURNING id""", id, token);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
        return new Subscriber(id, device, token);
    }

    public record Subscriber(UUID id, long deviceId, String token) {}

    /** An identity with a device but no consent: policy blocks it. */
    public UUID noConsent() {
        var s = subscriber();
        db.inTx(c -> Sql.update(c, "DELETE FROM consents WHERE identity_id = ?", s.id()));
        return s.id();
    }

    public void locale(UUID identityId, String locale) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO profiles (identity_id, attrs) VALUES (?, jsonb_build_object('locale', ?::text))
                ON CONFLICT (identity_id) DO UPDATE SET attrs = profiles.attrs || EXCLUDED.attrs""", identityId, locale));
    }

    public Map<String, Object> send(long sendId) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT idempotency_key, status::text, decision->>'reason' AS reason, config_snapshot_id,
                           delivered_count, failed_reason, cascade_run_id
                      FROM sends WHERE id = ?""", sendId);
                 var rs = ps.executeQuery()) {
                rs.next();
                var m = new java.util.HashMap<String, Object>();
                for (var col : new String[] {"idempotency_key", "status", "reason", "failed_reason"}) m.put(col, rs.getString(col));
                m.put("config_snapshot_id", Sql.nullableLong(rs, "config_snapshot_id"));
                m.put("delivered_count", Sql.nullableLong(rs, "delivered_count"));
                m.put("cascade_run_id", Sql.nullableLong(rs, "cascade_run_id"));
                return m;
            }
        });
    }

    public long count(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public void exec(String sql, Object... params) {
        db.inTx(c -> Sql.update(c, sql, params));
    }

    /** A queued sends row created at {@code createdAt}, as a crash mid-send would leave it. */
    public long queuedRow(UUID identityId, Instant createdAt) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO sends (identity_id, channel, category, template_key, idempotency_key, status, created_at)
                    VALUES (?, 'push', 'marketing', 'fixture', ?, 'queued', ?) RETURNING id""",
                    identityId, "test:" + UUID.randomUUID(), createdAt.atOffset(ZoneOffset.UTC));
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static UUID operator(java.sql.Connection c) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO operators (email, full_name, password_hash, status)
                VALUES ('orchestrator-test@example.com', 'Orchestrator Test', 'x', 'active')
                ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name
                RETURNING id""");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }
}
