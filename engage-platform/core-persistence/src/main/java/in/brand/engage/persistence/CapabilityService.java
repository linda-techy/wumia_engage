package in.brand.engage.persistence;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import jakarta.inject.Singleton;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * WhatsApp capability, learned from delivery outcomes: CLAUDE.md §4.2,
 * exactly. There is no API to ask whether a number has WhatsApp, and 131026
 * is a catch-all, so one failure never writes a customer off.
 *
 * <ul>
 * <li>Delivered, read or replied: {@code CAPABLE}, at once and for good.
 *     {@code CAPABLE} is sticky: a later 131026 does not demote it.</li>
 * <li>131026 on an authentication template to a {@code 91…} number: ignored
 *     (an Indian regulatory block, nothing to do with the number).</li>
 * <li>131049: Meta throttled us. {@code backoff_until} only; the state is untouched.</li>
 * <li>131026 on a utility template: one strike per IST calendar day. Two
 *     strikes on different days: {@code INCAPABLE}, rechecked after
 *     {@value #RECHECK_DAYS} days ({@link #expireIncapable}).</li>
 * <li>Everything else: noise.</li>
 * </ul>
 *
 * Strikes are a locked read-modify-write, so two failures arriving at once
 * for the same person on the same day are still one strike. Lives here, not
 * in the orchestrator, because delivery statuses arrive in ingest-api.
 */
@Singleton
public class CapabilityService {

    public static final int STRIKES_TO_INCAPABLE = 2;
    public static final int RECHECK_DAYS = 120;
    public static final Duration BACKOFF_131049 = Duration.ofHours(24);
    static final int UNDELIVERABLE = 131026;
    static final int MARKETING_LIMIT = 131049;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** On the caller's transaction: a status and what it teaches commit together. */
    public void observe(Connection c, UUID identityId, Channel channel, DeliveryOutcome o) throws SQLException {
        switch (o.type()) {
            case DELIVERED, READ, REPLIED -> markCapable(c, identityId, channel, o.at());
            case FAILED -> {
                Integer code = o.providerCode();
                if (code == null) return;
                if (code == UNDELIVERABLE && o.category() == Category.AUTHENTICATION
                        && o.msisdn() != null && o.msisdn().startsWith("91")) {
                    return;
                }
                if (code == MARKETING_LIMIT) {
                    backoff(c, identityId, channel, o.at().plus(BACKOFF_131049));
                    return;
                }
                if (code == UNDELIVERABLE && o.category() == Category.UTILITY) {
                    strike(c, identityId, channel, code, o.at());
                }
            }
        }
    }

    public void markCapable(Connection c, UUID identityId, Channel channel, Instant at) throws SQLException {
        Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel, state, decided_at, evidence)
                VALUES (?, CAST(? AS channel), 'CAPABLE', ?, jsonb_build_object('capable_at', ?::text))
                ON CONFLICT (identity_id, channel) DO UPDATE
                   SET state = 'CAPABLE', strike_days = '{}', recheck_after = NULL,
                       decided_at = COALESCE(CASE WHEN channel_capability.state = 'CAPABLE'
                                                  THEN channel_capability.decided_at END, EXCLUDED.decided_at),
                       evidence = channel_capability.evidence || EXCLUDED.evidence""",
                identityId, channel.dbName(), utc(at), at.toString());
    }

    void backoff(Connection c, UUID identityId, Channel channel, Instant until) throws SQLException {
        Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel, backoff_until) VALUES (?, CAST(? AS channel), ?)
                ON CONFLICT (identity_id, channel) DO UPDATE
                   SET backoff_until = GREATEST(channel_capability.backoff_until, EXCLUDED.backoff_until)""",
                identityId, channel.dbName(), utc(until));
    }

    void strike(Connection c, UUID identityId, Channel channel, int code, Instant at) throws SQLException {
        // Make sure a row exists, then lock it: concurrent statuses serialise here.
        Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel) VALUES (?, CAST(? AS channel))
                ON CONFLICT (identity_id, channel) DO NOTHING""", identityId, channel.dbName());
        String state;
        var days = new LinkedHashSet<LocalDate>();
        try (var ps = Sql.prepare(c, """
                SELECT state, strike_days FROM channel_capability
                 WHERE identity_id = ? AND channel = CAST(? AS channel) FOR UPDATE""", identityId, channel.dbName());
             var rs = ps.executeQuery()) {
            rs.next();
            state = rs.getString(1);
            Array arr = rs.getArray(2);
            if (arr != null) for (Object d : (Object[]) arr.getArray()) days.add(((Date) d).toLocalDate());
        }
        if (state.equals("CAPABLE") || state.equals("INCAPABLE")) return;   // sticky / already decided

        var day = at.atZone(IST).toLocalDate();
        if (!days.add(day)) return;                                          // same IST day: same signal
        var dayStrings = days.stream().sorted().map(LocalDate::toString).toArray(String[]::new);
        boolean incapable = days.size() >= STRIKES_TO_INCAPABLE;
        Sql.update(c, """
                UPDATE channel_capability
                   SET strike_days = CAST(? AS date[]),
                       state = CASE WHEN ? THEN 'INCAPABLE' ELSE state END,
                       decided_at = CASE WHEN ? THEN ? ELSE decided_at END,
                       recheck_after = CASE WHEN ? THEN ? ELSE recheck_after END,
                       evidence = evidence || jsonb_build_object('last_failure', jsonb_build_object('code', ?, 'at', ?::text))
                 WHERE identity_id = ? AND channel = CAST(? AS channel)""",
                dayStrings, incapable, incapable, utc(at), incapable, utc(at.plus(Duration.ofDays(RECHECK_DAYS))),
                code, at.toString(), identityId, channel.dbName());
    }

    /** INCAPABLE past its recheck date → UNKNOWN with no strikes. People do install WhatsApp. @return rows reset */
    public int expireIncapable(Connection c, Instant now) throws SQLException {
        return Sql.update(c, """
                UPDATE channel_capability
                   SET state = 'UNKNOWN', strike_days = '{}', recheck_after = NULL, decided_at = ?
                 WHERE state = 'INCAPABLE' AND recheck_after <= ?""", utc(now), utc(now));
    }

    private static java.time.OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
