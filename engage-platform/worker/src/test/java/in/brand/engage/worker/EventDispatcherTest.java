package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The event claim loop against Postgres. Scheduled jobs are off; the tests drive dispatchOnce(). */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class EventDispatcherTest {

    @Inject EventDispatcher dispatcher;
    @Inject WorkerTestBeans.Recording recording;
    @Inject WorkerTestBeans.Poison poison;
    @Inject Db db;
    @Inject DataSource dataSource;

    @BeforeEach void drainEarlierEvents() {
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            }
            // Events other test suites left undispatched: mark them so batches hold only ours.
            return Sql.update(c, "UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL");
        });
        recording.seen.clear();
        poison.attempts.set(0);
    }

    @Test void events_committed_out_of_id_order_by_three_concurrent_transactions_are_each_dispatched_exactly_once()
            throws Exception {
        var identity = identity();
        var t1 = open();
        var t2 = open();
        var t3 = open();
        var stop = new AtomicBoolean();
        var workers = new ArrayList<Thread>();
        // Two dispatchers race the whole time, as two worker pods would.
        for (int i = 0; i < 2; i++) {
            workers.add(Thread.ofPlatform().start(() -> {
                while (!stop.get()) dispatcher.dispatchOnce();
            }));
        }
        try {
            // Interleaved inserts: t1 takes the lowest ids and commits last.
            var ids = new ArrayList<Long>();
            for (int i = 0; i < 20; i++) {
                ids.add(insertEvent(t1, identity, "test_evt"));
                ids.add(insertEvent(t2, identity, "test_evt"));
                ids.add(insertEvent(t3, identity, "test_evt"));
            }
            t3.commit();
            Thread.sleep(50);
            t2.commit();
            Thread.sleep(50);
            t1.commit();

            long deadline = System.nanoTime() + 10_000_000_000L;
            while (undispatched(ids) > 0) {
                if (System.nanoTime() > deadline) fail(undispatched(ids) + " events never dispatched");
                Thread.sleep(20);
            }
            stop.set(true);
            for (var w : workers) w.join();

            assertEquals(60, ids.size());
            for (var id : ids) {
                assertEquals(1, recording.seen.getOrDefault(id, new java.util.concurrent.atomic.AtomicInteger()).get(),
                        "event " + id + " must reach its consumer exactly once");
            }
        } finally {
            stop.set(true);
            for (var c : List.of(t1, t2, t3)) c.close();
        }
    }

    @Test void a_poison_event_is_given_up_after_five_failures_and_does_not_stall_the_others() {
        var identity = identity();
        long bad = db.inTx(c -> insertEvent(c, identity, "test_poison"));
        long good = db.inTx(c -> insertEvent(c, identity, "test_evt"));

        dispatcher.dispatchOnce();
        assertEquals(1, recording.seen.get(good).get(), "the good event goes through on the first pass");
        assertFalse(dispatched(bad));

        for (int i = 0; i < EventDispatcher.MAX_FAILURES; i++) dispatcher.dispatchOnce();

        assertTrue(dispatched(bad), "given up and marked, so it no longer blocks the head of the stream");
        assertEquals(EventDispatcher.MAX_FAILURES, poison.attempts.get());
        assertEquals(1, recording.seen.get(good).get());
    }

    @Test void an_event_starts_its_intent_once_and_the_step_runs_after_the_commit() {
        var identity = identity();                       // no device: policy blocks the step, which proves it ran
        long e = db.inTx(c -> insertEvent(c, identity, "test_intent"));

        dispatcher.dispatchOnce();
        dispatcher.dispatchOnce();

        assertTrue(dispatched(e));
        assertEquals("exhausted|blocked:UNREACHABLE", runOutcome("evt:" + e));
        assertEquals(1L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ?", "evt:" + e));
    }

    @Test void when_a_consumer_fails_the_runs_its_event_started_are_rolled_back_with_it() {
        var identity = identity();
        long e = db.inTx(c -> insertEvent(c, identity, "test_atomic"));

        dispatcher.dispatchOnce();

        assertFalse(dispatched(e), "retried later");
        assertEquals(0L, count("SELECT count(*) FROM cascade_runs WHERE subject_key = ?", "evt:" + e),
                "a run from a rolled-back event would be sent again on the retry");
        for (int i = 0; i < EventDispatcher.MAX_FAILURES; i++) dispatcher.dispatchOnce();
        assertTrue(dispatched(e));
    }

    /* -------------------------------- helpers -------------------------------- */

    private UUID identity() {
        var id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
        return id;
    }

    private Connection open() throws SQLException {
        var c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    private static long insertEvent(Connection c, UUID identity, String name) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO events (identity_id, name, props, source) VALUES (?, ?, '{"k":"v"}', 'test') RETURNING id""",
                identity, name);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long undispatched(List<Long> ids) {
        return count("SELECT count(*) FROM events WHERE id = ANY(CAST(? AS bigint[])) AND dispatched_at IS NULL",
                (Object) ids.stream().map(String::valueOf).toArray(String[]::new));
    }

    private boolean dispatched(long eventId) {
        return count("SELECT count(*) FROM events WHERE id = ? AND dispatched_at IS NOT NULL", eventId) == 1;
    }

    private String runOutcome(String subject) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT status || '|' || outcome FROM cascade_runs WHERE subject_key = ?", subject);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private long count(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
