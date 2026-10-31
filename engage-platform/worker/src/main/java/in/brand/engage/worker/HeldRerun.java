package in.brand.engage.worker;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.policy.ConfigSnapshot;
import io.micronaut.context.ApplicationContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code worker journeys-rerun-held [--yes]}: abandoned cart and checkout
 * journeys that ended without sending anything because the customer was in a
 * holdout (control group), started again now that the holdout is off (owner,
 * 2026-10-31). Dry run without {@code --yes}.
 *
 * <p>Nothing is sent from here: each journey gets a fresh {@code cart_updated}
 * or {@code checkout_updated} event, and the worker runs it the usual way. The
 * journey looks at the cart or checkout as it is now, so a customer who ordered
 * since, emptied the cart or paid is skipped there; consent, caps and quiet
 * hours apply as to any message. Journeys of the last 7 days only, each once.
 */
public final class HeldRerun {

    static final String SOURCE = "rerun_held";
    static final String HOLDOUTS = "('HOLDOUT_CONTROL', 'HOLDOUT_GLOBAL')";

    /** A journey to start again: its run, intent and subject (cart or checkout token). */
    public record Held(long runId, String intent, UUID identityId, String subject) {}

    /** What a pass found and did. */
    public record Result(List<Held> started, List<Held> stillHeldOut) {}

    private HeldRerun() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].equals("journeys-rerun-held");
    }

    static void run(String[] args) {
        boolean yes = args.length > 1 && args[1].equals("--yes");
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            var db = ctx.getBean(Db.class);
            var events = ctx.getBean(EventWriter.class);
            var snapshot = ctx.getBean(ConfigResolver.class).snapshot();
            var r = db.inTx(c -> rerun(c, events, snapshot, yes));
            System.out.printf("journeys-rerun-held: %d journey(s) %s; %d skipped, the holdout is still on for them%n",
                    r.started().size(), yes ? "started again" : "to start again (dry run, add --yes)", r.stillHeldOut().size());
            for (var h : r.started()) System.out.printf("  %s run %d%n", h.intent(), h.runId());
            for (var h : r.stillHeldOut()) System.out.printf("  skipped %s run %d%n", h.intent(), h.runId());
        }
    }

    /**
     * Finds the journeys every message of which was held out, and with
     * {@code write} gives each a fresh event (deduped per run: a second pass
     * starts nothing twice). Skipped while that journey's holdout is on: the
     * customer would only be held out again.
     */
    public static Result rerun(Connection c, EventWriter events, ConfigSnapshot snapshot, boolean write) throws SQLException {
        var started = new ArrayList<Held>();
        var still = new ArrayList<Held>();
        boolean globalOff = snapshot.decimalValue("holdout.global_pct", "*").signum() == 0;
        for (var h : find(c)) {
            BigDecimal pct = snapshot.decimalValue("holdout.journey_pct", h.intent());
            if (pct.signum() > 0 || !globalOff) {
                still.add(h);
                continue;
            }
            if (write) {
                var token = h.intent().equals("checkout_abandon") ? "checkout_token" : "cart_token";
                events.write(c, h.identityId(), h.intent().equals("checkout_abandon") ? "checkout_updated" : "cart_updated",
                        SOURCE, SOURCE + ":" + h.runId(), Map.of(token, h.subject()));
            }
            started.add(h);
        }
        return new Result(started, still);
    }

    /** Ended (exhausted) in the last 7 days, held out at least once, nothing sent, and no later run on the same cart or checkout. */
    static List<Held> find(Connection c) throws SQLException {
        var out = new ArrayList<Held>();
        try (var ps = Sql.prepare(c, """
                SELECT r.id, r.intent_key, r.identity_id, r.subject_key FROM cascade_runs r
                 WHERE r.intent_key IN ('cart_recovery', 'checkout_abandon') AND r.status = 'exhausted'
                   AND r.updated_at > now() - interval '7 days'
                   AND EXISTS (SELECT 1 FROM sends s WHERE s.cascade_run_id = r.id AND s.decision->>'reason' IN %s)
                   AND NOT EXISTS (SELECT 1 FROM sends s WHERE s.cascade_run_id = r.id
                                      AND s.status IN ('queued', 'sent', 'delivered', 'read', 'clicked'))
                   AND NOT EXISTS (SELECT 1 FROM cascade_runs l WHERE l.intent_key = r.intent_key
                                      AND l.subject_key = r.subject_key AND l.id > r.id)
                   AND NOT EXISTS (SELECT 1 FROM events e WHERE e.dedupe_key = ? || ':' || r.id)
                 ORDER BY r.id""".formatted(HOLDOUTS), SOURCE); var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Held(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)));
            }
        }
        return out;
    }
}
