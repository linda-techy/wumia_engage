package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.ChannelException;
import in.brand.engage.persistence.Db;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T07: the cascade tests of CLAUDE.md §10, plus a cancel while claimed and escalation. Noon IST. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CascadeRunnerTest {

    static final java.util.Map<String, String> VARS = java.util.Map.of(
            "product", "Linen Kurta", "collection", "Linen", "url", "https://w.example/p/linen-kurta");

    @Inject DefaultOrchestrator orchestrator;
    @Inject FakePushAdapter fcm;
    @Inject OrchestratorTestData data;
    @Inject TestClock clock;
    @Inject Db db;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        data.resetConfig();
        fcm.reset();
    }

    CascadeRun dispatch(UUID identity, String intent) {
        return orchestrator.dispatch(new MessageIntent(identity, intent, "subj:" + UUID.randomUUID(), VARS, null));
    }

    CascadeRun run(long id) {
        return orchestrator.find(id).orElseThrow();
    }

    List<String> attempts(long runId) {
        return db.inTx(c -> {
            var out = new java.util.ArrayList<String>();
            try (var ps = in.brand.engage.persistence.Sql.prepare(c, """
                    SELECT step_index || ':' || channel || ':' || result || COALESCE(':' || reason, '')
                      FROM cascade_attempts WHERE run_id = ? ORDER BY id""", runId); var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    @Test void guard_failure_skips_step_without_consuming_wait() {
        var s = data.subscriber();                                // push consent, no WhatsApp marketing
        var run = dispatch(s.id(), TestCascades.GUARDED);

        assertEquals(List.of("0:push:skipped:guard:WA_MARKETING", "1:push:sent"), attempts(run.id()));
        assertEquals("exhausted|sent", run.status() + "|" + run.outcome(), "step 2 ran at once, not 4 h later");
        assertEquals(1, fcm.sent.size());
    }

    @Test void success_signal_terminates_remaining_steps() {
        var s = data.subscriber();
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        assertEquals("waiting", run.status());

        db.inTx(c -> orchestrator.onSignal(c, run.id(), "clicked"));
        clock.setIst("2026-09-28T17:00:00");
        orchestrator.tick(200);

        assertEquals("succeeded", run(run.id()).status());
        assertEquals(1, fcm.sent.size(), "the 4 h step never went");
    }

    @Test void a_signal_the_cascade_does_not_count_changes_nothing() {
        var s = data.subscriber();
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        db.inTx(c -> orchestrator.onSignal(c, run.id(), "read"));   // promotional: a read is not engagement
        assertEquals("waiting", run(run.id()).status());
    }

    @Test void cancel_event_terminates_within_one_tick() {
        var s = data.subscriber();
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        db.inTx(c -> orchestrator.cancel(c, TestCascades.TWO_STEP, subjectOf(run.id()), "order_placed"));

        clock.setIst("2026-09-28T17:00:00");
        orchestrator.tick(200);
        assertEquals("cancelled|order_placed", run(run.id()).status() + "|" + run(run.id()).outcome());
        assertEquals(1, fcm.sent.size());
    }

    @Test void a_cancel_committed_while_the_run_is_claimed_ends_it_and_no_later_step_is_sent() {
        var s = data.subscriber();
        // The cancel lands while step 1's send is in flight, as a concurrent order_placed would.
        fcm.answer((m, a) -> {
            data.exec("UPDATE cascade_runs SET status = 'cancelled', outcome = 'order_placed' WHERE identity_id = ? "
                    + "AND status IN ('active','waiting')", s.id());
            return new in.brand.engage.channels.DispatchResult(null, 1, List.of());
        });
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        assertEquals("cancelled", run.status(), "the step's own update did not resurrect it");

        fcm.reset();
        clock.setIst("2026-09-28T17:00:00");
        orchestrator.tick(200);
        assertEquals("cancelled", run(run.id()).status());
        assertEquals(0, fcm.sent.size(), "step 2 never went");
    }

    @Test void policy_defer_reschedules_same_step_max_three_times() {
        clock.setIst("2026-09-28T22:00:00");                         // quiet hours: marketing defers
        var s = data.subscriber();
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, run(run.id()).stepIndex(), "deferral " + (i + 1) + " keeps the same step");
            // Bring the deferred step due again, still inside quiet hours.
            data.exec("UPDATE cascade_runs SET next_step_at = ? WHERE id = ?",
                    clock.instant().minusSeconds(60).atOffset(ZoneOffset.UTC), run.id());
            orchestrator.tick(200);
        }
        var after = run(run.id());
        assertEquals(1, after.stepIndex(), "the 4th deferral moves on to the next step");
        assertEquals(4L, data.count("SELECT count(*) FROM cascade_attempts WHERE run_id = ? AND step_index = 0 AND result = 'deferred'", run.id()));
        assertEquals(0, fcm.sent.size(), "never dropped, never sent in quiet hours");
    }

    @Test void incapable_whatsapp_routes_directly_to_sms() {
        var s = data.subscriber();
        data.exec("INSERT INTO channel_capability (identity_id, channel, state, strike_days, recheck_after) "
                + "VALUES (?, 'whatsapp', 'INCAPABLE', '{2026-09-01,2026-09-03}', now() + interval '100 days')", s.id());

        var run = dispatch(s.id(), TestCascades.TRACKING);

        var a = attempts(run.id());
        assertEquals("0:whatsapp:skipped:guard:WA_CAPABLE_OR_UNKNOWN", a.getFirst(), "no WhatsApp attempt is made");
        assertTrue(a.get(1).startsWith("1:sms:"), "the SMS step ran at once: " + a);
        assertEquals(0L, data.count("SELECT count(*) FROM sends WHERE identity_id = ? AND channel = 'whatsapp'", s.id()));
    }

    @Test void ios_web_identity_never_gets_a_push_step() {
        var s = data.subscriber();
        data.exec("UPDATE devices SET platform = 'IOS_WEB' WHERE identity_id = ?", s.id());

        var run = dispatch(s.id(), TestCascades.IOS);

        assertEquals(List.of("0:push:skipped:guard:HAS_LIVE_DEVICE"), attempts(run.id()));
        assertEquals(0, fcm.sent.size());
    }

    @Test void an_escalating_failure_moves_to_the_next_step_at_once() {
        var s = data.subscriber();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        fcm.answer((m, a) -> {
            if (calls.getAndIncrement() == 0) throw new ChannelException.Permanent("wa_131026", "undeliverable", false, true, List.of());
            return new in.brand.engage.channels.DispatchResult(null, 1, List.of());
        });
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        assertEquals(List.of("0:push:failed:wa_131026", "1:push:sent"), attempts(run.id()), "no 4 h wait after an escalating failure");
    }

    @Test void a_131026_status_arriving_later_brings_the_next_step_forward() {
        var s = data.subscriber();
        var run = dispatch(s.id(), TestCascades.TWO_STEP);
        long sendId = data.count("SELECT send_id FROM cascade_attempts WHERE run_id = ? AND step_index = 0", run.id());

        boolean first = db.inTx(c -> orchestrator.escalate(c, sendId));
        assertTrue(first);
        orchestrator.tick(200);                                      // same instant: no 4 h wait
        assertEquals(2, fcm.sent.size());
        boolean again = db.inTx(c -> orchestrator.escalate(c, sendId));
        assertFalse(again, "a second escalation does nothing");
    }

    String subjectOf(long runId) {
        return db.inTx(c -> {
            try (var ps = in.brand.engage.persistence.Sql.prepare(c, "SELECT subject_key FROM cascade_runs WHERE id = ?", runId);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    static Duration hours(int h) {
        return Duration.ofHours(h);
    }
}
