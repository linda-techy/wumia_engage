package in.brand.engage.ingest.inbox;

import in.brand.engage.core.privacy.CustomerAllowlist;
import in.brand.engage.persistence.Db;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The webhook inbox (V1 {@code webhook_inbox}). */
@Singleton
public class InboxRepository {

    public record Item(String source, String deliveryId, String topic, int attempts) {}

    /** FILTERED: verified, but not an allowlisted customer. Acknowledged and never stored. */
    public enum Stored { NEW, DUPLICATE, FILTERED }

    /** After this many failed attempts an item is a dead letter and waits for a human. */
    public static final int MAX_ATTEMPTS = 10;

    private final Db db;
    private final CustomerAllowlist allowlist;

    public InboxRepository(Db db, CustomerAllowlist allowlist) {
        this.db = db;
        this.allowlist = allowlist;
    }

    /**
     * Store a verified webhook. The primary key (source, delivery_id) makes a
     * provider retry a no-op. The topic is read from the payload when the
     * provider does not send it as a header (Razorpay).
     *
     * <p>Customer allowlist, applied before anything is written:
     * <ul>
     *   <li>payload carries emails → stored only if <b>every</b> one is allowlisted;</li>
     *   <li>no email but a phone, customer id, or a customers/* topic → dropped, since it
     *       cannot be attributed to an allowlisted customer;</li>
     *   <li>no customer identifiers at all (an anonymous cart) → stored: it holds no
     *       personal data and only links to someone through an allowlisted checkout.</li>
     * </ul>
     */
    public Stored store(String source, String deliveryId, String topicOrNull, byte[] rawBody) {
        var json = new String(rawBody, StandardCharsets.UTF_8);
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    WITH x AS (SELECT ?::jsonb AS p),
                    ids AS (
                      SELECT array_remove(ARRAY[
                               NULLIF(lower(btrim(p->>'email')), ''),
                               NULLIF(lower(btrim(p->>'contact_email')), ''),
                               NULLIF(lower(btrim(p#>>'{customer,email}')), ''),
                               NULLIF(lower(btrim(p#>>'{payload,payment,entity,email}')), '')], NULL) AS emails,
                             (   NULLIF(btrim(p->>'phone'), '') IS NOT NULL
                              OR NULLIF(btrim(p#>>'{customer,phone}'), '') IS NOT NULL
                              OR NULLIF(btrim(p#>>'{billing_address,phone}'), '') IS NOT NULL
                              OR NULLIF(btrim(p#>>'{shipping_address,phone}'), '') IS NOT NULL
                              OR NULLIF(btrim(p#>>'{payload,payment,entity,contact}'), '') IS NOT NULL
                              OR p#>>'{customer,id}' IS NOT NULL
                              OR COALESCE(?, '') LIKE 'customers/%') AS other_ids
                        FROM x),
                    ok AS (
                      SELECT CASE WHEN ? THEN true
                                  WHEN cardinality(emails) > 0 THEN emails <@ ?::text[]
                                  ELSE NOT other_ids END AS allowed
                        FROM ids),
                    ins AS (
                      INSERT INTO webhook_inbox (source, delivery_id, topic, payload)
                      SELECT ?, ?, COALESCE(?, p->>'event', 'unknown'), p
                        FROM x, ok WHERE ok.allowed
                      ON CONFLICT (source, delivery_id) DO NOTHING
                      RETURNING 1)
                    SELECT (SELECT allowed FROM ok), (SELECT count(*) FROM ins)""")) {
                ps.setString(1, json);
                ps.setString(2, topicOrNull);
                ps.setBoolean(3, allowlist.allowAll());
                ps.setArray(4, c.createArrayOf("text", allowlist.emails().toArray()));
                ps.setString(5, source);
                ps.setString(6, deliveryId);
                ps.setString(7, topicOrNull);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) return Stored.FILTERED;
                    return rs.getLong(2) == 1 ? Stored.NEW : Stored.DUPLICATE;
                }
            }
        });
    }

    /**
     * Claim due items with a lease: next_attempt_at moves 5 minutes out, so
     * another pod will not pick the same row while this one works on it. If
     * this pod dies mid-way, the lease expires and the item is retried.
     */
    public List<Item> claim(int limit) {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE webhook_inbox w
                       SET attempts = w.attempts + 1,
                           next_attempt_at = now() + interval '5 minutes'
                      FROM (SELECT source, delivery_id
                              FROM webhook_inbox
                             WHERE processed_at IS NULL
                               AND next_attempt_at <= now()
                               AND attempts < ?
                             ORDER BY received_at
                             LIMIT ?
                             FOR UPDATE SKIP LOCKED) due
                     WHERE w.source = due.source AND w.delivery_id = due.delivery_id
                 RETURNING w.source, w.delivery_id, w.topic, w.attempts""")) {
                ps.setInt(1, MAX_ATTEMPTS);
                ps.setInt(2, limit);
                var items = new ArrayList<Item>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
                    }
                }
                return items;
            }
        });
    }

    /** Marks done inside the handler's own transaction, so work and completion commit together. */
    public void markProcessed(Connection c, Item item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE webhook_inbox SET processed_at = now(), last_error = NULL
                 WHERE source = ? AND delivery_id = ?""")) {
            ps.setString(1, item.source());
            ps.setString(2, item.deliveryId());
            ps.executeUpdate();
        }
    }

    /** Exponential backoff: 30s, 1m, 2m ... capped at 1h. After 10 tries it stays for a human. */
    public void markFailed(Item item, String error) {
        long delaySeconds = Math.min(3600, 30L << Math.min(item.attempts() - 1, 7));
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE webhook_inbox
                       SET last_error = ?, next_attempt_at = now() + make_interval(secs => ?)
                     WHERE source = ? AND delivery_id = ?""")) {
                ps.setString(1, error == null ? "unknown" : error.substring(0, Math.min(error.length(), 1000)));
                ps.setLong(2, delaySeconds);
                ps.setString(3, item.source());
                ps.setString(4, item.deliveryId());
                return ps.executeUpdate();
            }
        });
    }
}
