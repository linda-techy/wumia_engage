package in.brand.engage.policy;

import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Journeys collide by priority (phase-5 §5, P5-T01): a step of a cascade
 * with priority P is blocked {@code HIGHER_PRIORITY_ACTIVE} while the same
 * person has another cascade of priority 2 or 3 that is more important
 * (lower number), live or ended less than {@code journey.collision.suppress_hours}
 * ago. So a shopper in checkout (2) gets no cart (3) or win-back (4) message,
 * and someone who just finished a checkout journey gets a quiet half-day.
 *
 * <p>Priority 1 (transactional: order updates, payment failure) never blocks
 * and is never blocked. A send outside a cascade is not checked. Blocked, not
 * deferred: the step is skipped and the cascade moves on.
 */
@Singleton
public class CollisionCheck {

    /** The more important run that blocks this one, if any. */
    public record Blocker(String intentKey, long runId) {}

    public Optional<Blocker> blocker(Connection c, long runId, Instant now, Duration suppress) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT o.intent_key, o.id
                  FROM cascade_runs me
                  JOIN cascade_runs o ON o.identity_id = me.identity_id AND o.id <> me.id
                 WHERE me.id = ? AND me.priority > 1
                   AND o.priority IN (2, 3) AND o.priority < me.priority
                   AND (o.status IN ('active', 'waiting') OR o.updated_at > ?)
                 ORDER BY o.priority, o.id DESC
                 LIMIT 1""", runId, now.minus(suppress).atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(new Blocker(rs.getString(1), rs.getLong(2))) : Optional.empty();
        }
    }
}
