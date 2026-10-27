package in.brand.engage.worker.inbox;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.StaffPushKeys;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "New WhatsApp message" on the phones and computers of staff who turned
 * alerts on in the inbox (V50). The push carries no content: the console's
 * service worker shows a fixed notification and opens the inbox, so no
 * customer name or text passes through Google's or Apple's push services.
 * Outside support hours alerts wait; the first pass after opening sends one.
 * Menu taps the auto-reply answered (track, bestsellers) alert nobody.
 */
@Singleton
public class StaffAlerts {

    private static final Logger LOG = LoggerFactory.getLogger(StaffAlerts.class);

    static final String SUBJECT = "mailto:support@wumika.com";
    static final int TTL_SECONDS = 3600;
    static final int MAX_FAILURES = 10;

    record Target(long id, String endpoint) {}

    private record Plan(StaffPushKeys.Keys keys, List<Target> targets) {}

    private final Db db;
    private final PushTransport push;
    private final InboxSettings settings;
    private final Clock clock;

    public StaffAlerts(Db db, PushTransport push, InboxSettings settings, Clock clock) {
        this.db = db;
        this.push = push;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return devices alerted */
    public int runOnce() {
        var now = clock.instant();
        var plan = db.inTx(c -> {
            long mark = InboxJobState.lock(c, "staff_alert");
            long newest;
            long worth;
            try (var ps = Sql.prepare(c, """
                    SELECT COALESCE(max(id), 0),
                           count(*) FILTER (WHERE kind NOT IN ('reaction', 'button')
                                              AND NOT (kind = 'interactive' AND body IN (?, ?)))
                      FROM wa_messages WHERE direction = 'in' AND id > ?""",
                    InboxAutoReply.TRACK, InboxAutoReply.SHOP, mark); var rs = ps.executeQuery()) {
                rs.next();
                newest = rs.getLong(1);
                worth = rs.getLong(2);
            }
            if (newest == 0 || !settings.hours(c).open(now)) return null;
            InboxJobState.advance(c, "staff_alert", newest);
            if (worth == 0) return null;
            var targets = new ArrayList<Target>();
            try (var ps = Sql.prepare(c, """
                    SELECT s.id, s.endpoint FROM staff_push_subscriptions s JOIN operators o ON o.id = s.operator_id
                     WHERE o.status = 'active'"""); var rs = ps.executeQuery()) {
                while (rs.next()) targets.add(new Target(rs.getLong(1), rs.getString(2)));
            }
            return targets.isEmpty() ? null : new Plan(StaffPushKeys.load(c), targets);
        });
        if (plan == null) return 0;

        int ok = 0;
        for (var t : plan.targets()) {
            int status;
            try {
                var u = URI.create(t.endpoint());
                status = push.post(t.endpoint(), plan.keys().authorization(u.getScheme() + "://" + u.getHost(), SUBJECT, now), TTL_SECONDS, "inbox");
            } catch (IOException | RuntimeException e) {
                status = -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ok;
            }
            final int s = status;
            if (s / 100 == 2) ok++;
            db.inTx(c -> {
                if (s / 100 == 2) {
                    return Sql.update(c, "UPDATE staff_push_subscriptions SET last_sent_at = now(), failures = 0 WHERE id = ?", t.id());
                }
                if (s == 404 || s == 410) {
                    return Sql.update(c, "DELETE FROM staff_push_subscriptions WHERE id = ?", t.id());   // the browser unsubscribed
                }
                Sql.update(c, "UPDATE staff_push_subscriptions SET failures = failures + 1 WHERE id = ?", t.id());
                return Sql.update(c, "DELETE FROM staff_push_subscriptions WHERE id = ? AND failures >= ?", t.id(), MAX_FAILURES);
            });
            if (s / 100 != 2) LOG.warn("staff alert to subscription {} answered HTTP {}", t.id(), s);
        }
        return ok;
    }

}
