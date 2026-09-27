package in.brand.engage.policy;

import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Holdout buckets. Assignment is deterministic (a hash, not a random draw), so
 * it can be reproduced when the result is analysed, and it is written to
 * {@code holdouts} on first evaluation, so a later change of percentage never
 * moves anyone who was already assigned. That write is the only side effect of
 * {@link PolicyEngine#decide}.
 *
 * <p>A percentage of 0 means no holdout is running: nothing is evaluated or written.
 */
@Singleton
public class Holdouts {

    public static final String GLOBAL = "global";

    public enum Bucket { CONTROL, TREATMENT }

    public Bucket bucket(Connection c, UUID identityId, String experiment, BigDecimal pct) throws SQLException {
        var fresh = assign(experiment, identityId, pct).name().toLowerCase(java.util.Locale.ROOT);
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var ps = Sql.prepare(c, """
                    WITH ins AS (
                      INSERT INTO holdouts (identity_id, experiment, bucket) VALUES (?, ?, ?)
                      ON CONFLICT (identity_id, experiment) DO NOTHING
                      RETURNING bucket)
                    SELECT bucket FROM ins
                    UNION ALL
                    SELECT bucket FROM holdouts WHERE identity_id = ? AND experiment = ?
                    LIMIT 1""", identityId, experiment, fresh, identityId, experiment);
                 var rs = ps.executeQuery()) {
                // Empty only if a concurrent first assignment committed after this
                // statement's snapshot was taken: the second attempt sees it.
                if (rs.next()) return Bucket.valueOf(rs.getString(1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        throw new IllegalStateException("no holdout row for " + identityId + " in " + experiment);
    }

    /** The assignment a first evaluation would make at this percentage. */
    static Bucket assign(String experiment, UUID identityId, BigDecimal pct) {
        var threshold = pct.movePointRight(2);                      // 5.0 % → 500 of 10,000 slots
        return BigDecimal.valueOf(slot(experiment, identityId)).compareTo(threshold) < 0
                ? Bucket.CONTROL : Bucket.TREATMENT;
    }

    /** First 8 bytes of SHA-256("experiment:identity_id"), unsigned, mod 10,000. */
    static int slot(String experiment, UUID identityId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((experiment + ":" + identityId).getBytes(StandardCharsets.UTF_8));
            return (int) Long.remainderUnsigned(ByteBuffer.wrap(digest, 0, 8).getLong(), 10_000);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
