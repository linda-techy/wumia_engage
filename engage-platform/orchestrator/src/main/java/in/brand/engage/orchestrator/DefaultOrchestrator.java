package in.brand.engage.orchestrator;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs cascades. P3 scope: {@link #dispatch} creates the run and runs step 0
 * at once if it is due; {@link #tick} runs steps that became due later
 * (deferrals, delayed intents). The multi-step runner with guards and success
 * signals is P4-T06.
 *
 * <p>A step is claimed with a lease (next_step_at pushed {@link #LEASE} ahead)
 * before it runs, so dispatch and a concurrent tick never run it twice; the
 * per-step idempotency key {@code run:<id>:step:<n>} backs that up.
 */
@Singleton
public class DefaultOrchestrator implements MessageOrchestrator {

    static final Duration LEASE = Duration.ofMinutes(5);
    /** CLAUDE.md §3.2: a Defer reschedules the same step at most three times; the next Defer skips it. */
    static final int MAX_DEFERRALS = 3;

    private final Db db;
    private final CascadeDefinitions definitions;
    private final MessageRouter router;
    private final Clock clock;

    public DefaultOrchestrator(Db db, CascadeDefinitions definitions, MessageRouter router, Clock clock) {
        this.db = db;
        this.definitions = definitions;
        this.router = router;
        this.clock = clock;
    }

    @Override
    public CascadeRun dispatch(MessageIntent intent) {
        var created = db.inTx(c -> enqueue(c, intent));
        if (created.isEmpty()) {
            // One live run per intent and subject: the dedupe guarantee.
            return live(intent.intentKey(), intent.subjectKey()).orElseThrow(() ->
                    new IllegalStateException("live run for " + intent.intentKey() + "/" + intent.subjectKey() + " vanished"));
        }
        return runIfDue(created.get().id()).orElse(created.get());
    }

    /**
     * Creates the run on the caller's connection, inside the caller's
     * transaction, and sends nothing. The event dispatcher uses this so an
     * event and the runs it starts commit together, exactly once; it then
     * calls {@link #runIfDue} after the commit.
     *
     * @return the new run, or empty if a live run for this intent and subject exists
     */
    public Optional<CascadeRun> enqueue(Connection c, MessageIntent intent) throws SQLException {
        var def = definitions.find(intent.intentKey())
                .orElseThrow(() -> new IllegalArgumentException("no cascade definition for intent " + intent.intentKey()));
        var now = clock.instant();
        var due = intent.notBefore() == null || !intent.notBefore().isAfter(now) ? now : intent.notBefore();
        try (var ps = Sql.prepare(c, """
                INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, vars, next_step_at)
                VALUES (?, ?, ?, ?, jsonb_object(CAST(? AS text[]), CAST(? AS text[])), ?)
                ON CONFLICT (intent_key, subject_key) WHERE status IN ('active','waiting') DO NOTHING
                RETURNING *""",
                intent.intentKey(), intent.identityId(), intent.subjectKey(), def.priority().level(),
                intent.vars().keySet().toArray(String[]::new),
                intent.vars().keySet().stream().map(intent.vars()::get).toArray(String[]::new),
                utc(due));
             var rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(run(rs)) : Optional.empty();
        }
    }

    /** Runs the run's current step if it is due and nobody else holds it. @return the run afterwards, if it ran */
    public Optional<CascadeRun> runIfDue(long runId) {
        return claim(runId).map(this::runStep);
    }

    /** Runs every step that has come due. The worker calls this on a schedule (P3-T06). @return steps run */
    public int tick(int limit) {
        var claimed = db.inTx(c -> {
            var out = new ArrayList<CascadeRun>();
            // MATERIALIZED: the pick runs once, so the LIMIT holds (an IN subquery may be re-run).
            try (var ps = Sql.prepare(c, """
                    WITH picked AS MATERIALIZED (
                           SELECT id FROM cascade_runs
                            WHERE status IN ('active','waiting') AND next_step_at <= ?
                            ORDER BY next_step_at LIMIT ? FOR UPDATE SKIP LOCKED)
                    UPDATE cascade_runs r SET next_step_at = ?, updated_at = now()
                      FROM picked WHERE r.id = picked.id
                    RETURNING r.*""", utc(clock.instant()), limit, utc(clock.instant().plus(LEASE)));
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(run(rs));
            }
            return out;
        });
        claimed.forEach(this::runStep);
        return claimed.size();
    }

    @Override
    public void cancel(UUID identityId, String subjectKey, String reason) {
        db.inTx(c -> Sql.update(c, """
                UPDATE cascade_runs SET status = 'cancelled', outcome = ?, updated_at = now()
                 WHERE identity_id = ? AND subject_key = ? AND status IN ('active','waiting')""",
                reason, identityId, subjectKey));
    }

    /**
     * Cancels the live run of one intent for a subject, on the caller's
     * connection: an event consumer restarting a cascade (a cart changed) or
     * ending it (the order was placed) commits the cancel with the event.
     *
     * @return runs cancelled (0 or 1)
     */
    public int cancel(Connection c, String intentKey, String subjectKey, String reason) throws SQLException {
        return Sql.update(c, """
                UPDATE cascade_runs SET status = 'cancelled', outcome = ?, updated_at = now()
                 WHERE intent_key = ? AND subject_key = ? AND status IN ('active','waiting')""",
                reason, intentKey, subjectKey);
    }

    /**
     * A success signal for a run (push: a click). Success terminates the whole
     * cascade (CLAUDE.md §3.2): a live run stops before its next step, and a
     * single-step run that already finished is recorded as succeeded. A
     * cancelled or failed run is left alone.
     *
     * @return true if the run changed
     */
    public boolean onSignal(Connection c, long runId, String signal) throws SQLException {
        String intentKey;
        try (var ps = Sql.prepare(c, "SELECT intent_key FROM cascade_runs WHERE id = ?", runId); var rs = ps.executeQuery()) {
            if (!rs.next()) return false;
            intentKey = rs.getString(1);
        }
        // Only the intent's own success signals count (CLAUDE.md §3.3): a WhatsApp read ends an
        // order update, but not a promotional cascade, which needs a click or a reply.
        var def = definitions.find(intentKey).orElse(null);
        if (def == null || !def.successSignals().contains(signal)) return false;
        return Sql.update(c, """
                UPDATE cascade_runs SET status = 'succeeded', outcome = ?, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting','exhausted')""", signal, runId) > 0;
    }

    /** {@link #onSignal} for the run that produced a send (Meta statuses, SMS delivery reports). */
    public boolean onSendSignal(Connection c, long sendId, String signal) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT cascade_run_id FROM sends WHERE id = ? AND cascade_run_id IS NOT NULL", sendId);
             var rs = ps.executeQuery()) {
            return rs.next() && onSignal(c, rs.getLong(1), signal);
        }
    }

    /**
     * A send that failed later, by status webhook, with an "escalate now" code
     * (WhatsApp 131026 / 131049): the run already waits for its next step, so
     * bring that step forward to now. Nothing happens if the run has moved on,
     * ended, or the send was not the step just taken.
     *
     * @return true if a run was brought forward
     */
    public boolean escalate(Connection c, long sendId) throws SQLException {
        var now = utc(clock.instant());
        return Sql.update(c, """
                UPDATE cascade_runs r SET next_step_at = ?, updated_at = now()
                  FROM cascade_attempts a
                 WHERE a.send_id = ? AND a.run_id = r.id AND r.status = 'waiting'
                   AND r.step_index = a.step_index + 1 AND r.next_step_at > ?""", now, sendId, now) > 0;
    }

    public Optional<CascadeRun> find(long runId) {
        return db.inTx(c -> one(c, "SELECT * FROM cascade_runs WHERE id = ?", runId));
    }

    /* ------------------------------ internals ------------------------------ */

    private CascadeRun runStep(CascadeRun run) {
        var def = definitions.find(run.intentKey()).orElse(null);
        if (def == null) return finish(run, "failed", "no_definition");

        var current = run;
        while (true) {
            // A cancel or success committed while this run was claimed ends it here, before any send.
            var fresh = find(current.id()).orElse(current);
            if (!live(fresh)) return fresh;
            if (current.stepIndex() >= def.steps().size()) return finish(current, "exhausted", "no_steps_left");
            var step = def.steps().get(current.stepIndex());
            boolean last = current.stepIndex() == def.steps().size() - 1;

            if (!step.guards().isEmpty()) {
                var failing = step.firstFailing(context(current, def));
                if (failing != null) {
                    // Skipped, not failed, and the wait is not consumed (CLAUDE.md §3.2).
                    attempt(current, step, null, "skipped", "guard:" + failing.name());
                    if (last) return finish(current, "exhausted", "skipped:" + failing.name());
                    current = skip(current);
                    continue;
                }
            }

            var vars = vars(current.id());
            var result = router.send(new SendCommand(current.identityId(), current.intentKey(), current.id(),
                    current.stepIndex(), step.channel(), step.templateFor(vars), vars,
                    "run:" + current.id() + ":step:" + current.stepIndex(),
                    step.ttl(), step.highUrgency(), step.allowStaleDevices(), step.reply()));

            switch (result) {
                case SendResult.Sent s -> {
                    attempt(current, step, s.sendId(), "sent", null);
                    return last ? finish(current, "exhausted", "sent")
                                : advance(current, clock.instant().plus(step.waitAfter()));
                }
                case SendResult.Duplicate d -> {
                    // Already attempted (e.g. a crash after the send, before this update).
                    attempt(current, step, d.sendId(), "skipped", "duplicate:" + d.status());
                    return last ? finish(current, "exhausted", "duplicate")
                                : advance(current, clock.instant().plus(step.waitAfter()));
                }
                case SendResult.Failed f -> {
                    attempt(current, step, f.sendId(), "failed", f.code());
                    if (last) return finish(current, "failed", "failed:" + f.code());
                    // 131026 / 131049: escalate now; any other failure waits out the step like a send.
                    if (f.escalateImmediately()) {
                        current = skip(current);
                        continue;
                    }
                    return advance(current, clock.instant().plus(step.waitAfter()));
                }
                case SendResult.Deferred d -> {
                    attempt(current, step, d.sendId(), "deferred", d.reason().name());
                    if (current.deferrals() < MAX_DEFERRALS) return defer(current, d);
                    // Rescheduled MAX_DEFERRALS times already: stop and move on.
                    if (last) return finish(current, "exhausted", "deferred_limit:" + d.reason().name());
                    current = skip(current);
                }
                case SendResult.Blocked b -> {
                    // A blocked step is skipped, not failed: move on without waiting.
                    attempt(current, step, b.sendId(), "blocked", b.reason().name());
                    if (last) return finish(current, "exhausted", "blocked:" + b.reason().name());
                    current = skip(current);
                }
            }
        }
    }

    private static boolean live(CascadeRun run) {
        return run.status().equals("active") || run.status().equals("waiting");
    }

    /** What the step's guards look at, read once for this step. */
    private Guard.Context context(CascadeRun run, CascadeDefinition def) {
        var vars = vars(run.id());
        long cart = 0;
        try {
            cart = Long.parseLong(vars.getOrDefault("cart_value_paise", "0"));
        } catch (NumberFormatException ignored) {
            // not a number: no cart value
        }
        long cartValue = cart;
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT COALESCE((SELECT state FROM channel_capability
                                      WHERE identity_id = ? AND channel = 'whatsapp'), 'UNKNOWN') AS cap,
                           EXISTS (SELECT 1 FROM devices d
                                    WHERE d.identity_id = ? AND d.active AND d.platform <> 'IOS_WEB') AS device,
                           EXISTS (SELECT 1 FROM identity_keys k WHERE k.identity_id = ? AND k.kind = 'phone') AS phone,
                           ARRAY(SELECT channel || '/' || purpose FROM consent_current
                                  WHERE identity_id = ? AND state = 'granted') AS grants""",
                    run.identityId(), run.identityId(), run.identityId(), run.identityId());
                 var rs = ps.executeQuery()) {
                rs.next();
                var grants = new java.util.HashSet<String>();
                for (Object g : (Object[]) rs.getArray("grants").getArray()) grants.add(String.valueOf(g));
                return new Guard.Context(def.priority(), rs.getString("cap"), rs.getBoolean("device"),
                        rs.getBoolean("phone"), grants, cartValue, vars);
            }
        });
    }

    private Optional<CascadeRun> claim(long runId) {
        return db.inTx(c -> one(c, """
                UPDATE cascade_runs SET next_step_at = ?, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting') AND next_step_at <= ?
                RETURNING *""", utc(clock.instant().plus(LEASE)), runId, utc(clock.instant())));
    }

    private Optional<CascadeRun> live(String intentKey, String subjectKey) {
        return db.inTx(c -> one(c, """
                SELECT * FROM cascade_runs
                 WHERE intent_key = ? AND subject_key = ? AND status IN ('active','waiting')""", intentKey, subjectKey));
    }

    private void attempt(CascadeRun run, CascadeDefinition.Step step, Long sendId, String result, String reason) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO cascade_attempts (run_id, step_index, channel, send_id, result, reason)
                VALUES (?, ?, CAST(? AS channel), ?, ?, ?)""",
                run.id(), run.stepIndex(), step.channel().dbName(), sendId, result, reason));
    }

    private CascadeRun finish(CascadeRun run, String status, String outcome) {
        return update(run.id(), """
                UPDATE cascade_runs SET status = ?, outcome = ?, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting') RETURNING *""", status, outcome, run.id());
    }

    private CascadeRun defer(CascadeRun run, SendResult.Deferred d) {
        return update(run.id(), """
                UPDATE cascade_runs SET status = 'waiting', next_step_at = ?, deferrals = deferrals + 1, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting') RETURNING *""", utc(d.until()), run.id());
    }

    private CascadeRun advance(CascadeRun run, java.time.Instant next) {
        return update(run.id(), """
                UPDATE cascade_runs SET status = 'waiting', step_index = step_index + 1, deferrals = 0,
                                        next_step_at = ?, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting') RETURNING *""", utc(next), run.id());
    }

    private CascadeRun skip(CascadeRun run) {
        return update(run.id(), """
                UPDATE cascade_runs SET step_index = step_index + 1, deferrals = 0, updated_at = now()
                 WHERE id = ? AND status IN ('active','waiting') RETURNING *""", run.id());
    }

    /** A status change on a live run; if it ended meanwhile (cancelled, succeeded), it is returned as it now is. */
    private CascadeRun update(long runId, String sql, Object... params) {
        return db.inTx(c -> one(c, sql, params))
                .or(() -> find(runId))
                .orElseThrow(() -> new IllegalStateException("run " + runId + " vanished"));
    }

    private Map<String, String> vars(long runId) {
        return db.inTx(c -> {
            var out = new HashMap<String, String>();
            try (var ps = Sql.prepare(c, "SELECT key, value FROM cascade_runs, jsonb_each_text(vars) WHERE id = ?", runId);
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
            return out;
        });
    }

    private static Optional<CascadeRun> one(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(run(rs)) : Optional.empty();
        }
    }

    private static CascadeRun run(ResultSet rs) throws SQLException {
        return new CascadeRun(rs.getLong("id"), rs.getString("intent_key"), rs.getObject("identity_id", UUID.class),
                rs.getString("subject_key"), rs.getInt("priority"), rs.getInt("step_index"), rs.getString("status"),
                rs.getString("outcome"), rs.getObject("next_step_at", OffsetDateTime.class).toInstant(),
                rs.getInt("deferrals"));
    }

    private static OffsetDateTime utc(java.time.Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
