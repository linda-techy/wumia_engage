package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** dispatch(), tick() and cancel() against Postgres, with a fake push provider. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class DefaultOrchestratorTest {

    @Inject DefaultOrchestrator orchestrator;
    @Inject FakePushAdapter fcm;
    @Inject OrchestratorTestData data;
    @Inject TestClock clock;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        data.resetConfig();
        fcm.reset();
    }

    @Test void dispatch_runs_the_due_step_and_finishes_a_single_step_run() {
        var s = data.subscriber();

        var run = orchestrator.dispatch(intent(s.id(), subject(), null));

        assertEquals("exhausted", run.status());
        assertEquals("sent", run.outcome());
        assertEquals(1, fcm.sent.size());
        assertEquals(1L, data.count("""
                SELECT count(*) FROM cascade_attempts a JOIN sends s ON s.id = a.send_id
                 WHERE a.run_id = ? AND a.result = 'sent' AND s.cascade_run_id = ? AND s.status = 'sent'""",
                run.id(), run.id()));
    }

    @Test void a_blocked_step_ends_the_run_with_the_reason_and_sends_nothing() {
        var run = orchestrator.dispatch(intent(data.noConsent(), subject(), null));

        assertEquals("exhausted", run.status());
        assertEquals("blocked:NO_MARKETING_CONSENT", run.outcome());
        assertEquals(0, fcm.sent.size());
    }

    @Test void a_failed_send_fails_the_run() {
        var s = data.subscriber();
        fcm.answer((m, a) -> { throw new in.brand.engage.channels.ChannelException.Transient("fcm_call_failed", null, java.util.List.of(), null); });

        var run = orchestrator.dispatch(intent(s.id(), subject(), null));

        assertEquals("failed", run.status());
        assertEquals("failed:fcm_call_failed", run.outcome());
    }

    @Test void two_concurrent_dispatches_for_the_same_intent_and_subject_leave_one_live_run() throws Exception {
        var s = data.subscriber();
        var subject = subject();
        var later = clock.instant().plus(Duration.ofHours(4));      // keep the run live
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<CascadeRun>>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return orchestrator.dispatch(intent(s.id(), subject, later));
            }));
        }
        start.countDown();
        var ids = new HashSet<Long>();
        for (var f : futures) ids.add(f.get().id());
        pool.shutdown();

        assertEquals(1, ids.size(), "every caller gets the same run");
        assertEquals(1L, data.count("""
                SELECT count(*) FROM cascade_runs WHERE intent_key = ? AND subject_key = ?
                   AND status IN ('active','waiting')""", TestCascades.BROWSE, subject));
    }

    @Test void a_delayed_intent_waits_and_the_tick_sends_it_when_due() {
        var s = data.subscriber();
        var run = orchestrator.dispatch(intent(s.id(), subject(), clock.instant().plus(Duration.ofHours(4))));
        assertTrue(run.live());
        assertEquals(0, fcm.sent.size());

        clock.setIst("2026-09-28T16:01:00");
        orchestrator.tick(100);

        assertEquals("exhausted", orchestrator.find(run.id()).orElseThrow().status());
        assertEquals(1, fcm.sent.size());
    }

    @Test void a_quiet_hours_deferral_reschedules_the_step_and_the_tick_sends_it_in_the_morning() {
        var s = data.subscriber();
        clock.setIst("2026-09-28T22:40:00");

        var run = orchestrator.dispatch(intent(s.id(), subject(), null));

        assertEquals("waiting", run.status());
        assertEquals(TestClock.ist("2026-09-29T09:00:00"), run.nextStepAt());
        assertEquals(1, run.deferrals());

        clock.setIst("2026-09-28T23:30:00");
        orchestrator.tick(100);
        assertEquals(0, fcm.sent.size(), "not due before 09:00");

        clock.setIst("2026-09-29T09:01:00");
        orchestrator.tick(100);
        var done = orchestrator.find(run.id()).orElseThrow();
        assertEquals("exhausted", done.status());
        assertEquals("sent", done.outcome());
        assertEquals(1, fcm.sent.size(), "deferred, not dropped (invariant 5)");
    }

    @Test void after_three_reschedules_the_next_deferral_skips_the_step() {
        var s = data.subscriber();
        clock.setIst("2026-09-28T22:40:00");
        var run = orchestrator.dispatch(intent(s.id(), subject(), null));
        // Two more nights of deferral, compressed: the run has now been rescheduled three times.
        data.exec("UPDATE cascade_runs SET deferrals = 3, next_step_at = ? WHERE id = ?",
                clock.instant().atOffset(java.time.ZoneOffset.UTC), run.id());

        orchestrator.tick(100);

        var done = orchestrator.find(run.id()).orElseThrow();
        assertEquals("exhausted", done.status());
        assertEquals("deferred_limit:QUIET_HOURS", done.outcome());
        assertEquals(0, fcm.sent.size());
    }

    @Test void cancel_ends_the_live_run_for_that_subject() {
        var s = data.subscriber();
        var subject = subject();
        var run = orchestrator.dispatch(intent(s.id(), subject, clock.instant().plus(Duration.ofHours(4))));

        orchestrator.cancel(s.id(), subject, "order_placed");

        var cancelled = orchestrator.find(run.id()).orElseThrow();
        assertEquals("cancelled", cancelled.status());
        assertEquals("order_placed", cancelled.outcome());
        clock.setIst("2026-09-28T17:00:00");
        orchestrator.tick(100);
        assertEquals(0, fcm.sent.size());
    }

    @Test void an_unknown_intent_is_a_programming_error() {
        var s = data.subscriber();
        assertThrows(IllegalArgumentException.class, () -> orchestrator.dispatch(
                new MessageIntent(s.id(), "no_such_intent", subject(), Map.of(), null)));
    }

    private static MessageIntent intent(UUID identityId, String subject, java.time.Instant notBefore) {
        return new MessageIntent(identityId, TestCascades.BROWSE, subject, MessageRouterTest.VARS, notBefore);
    }

    private static String subject() {
        return "test-subject:" + UUID.randomUUID();
    }
}
