package in.brand.engage.worker;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds new {@code events} to the {@link EventConsumer}s.
 *
 * <p>Claims by {@code dispatched_at IS NULL} (V8), never by an id cursor: ids
 * are assigned at INSERT but become visible at COMMIT, so a cursor would skip
 * a row committed late. {@code FOR UPDATE SKIP LOCKED} lets several workers
 * share the stream without handing one event to two of them.
 *
 * <p>Each event runs in a savepoint inside the claim transaction. Its
 * consumers' writes, the cascade runs its intents start, and its
 * {@code dispatched_at} commit together, so an event starts its intents
 * exactly once. A consumer that throws rolls back only that event, which is
 * retried on the next pass; the rest of the batch goes through. After
 * {@link #MAX_FAILURES} failures the event is marked dispatched without
 * running consumers and logged at ERROR (alert on that line): one poison
 * event must not stall the stream. Steps are run only after the commit, so a
 * rollback can never leave a message sent for an event that is retried.
 */
@Singleton
public class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    public static final int BATCH = 200;
    public static final int MAX_FAILURES = 5;

    private final Db db;
    private final List<EventConsumer> consumers;
    private final DefaultOrchestrator orchestrator;
    /** In memory, per worker: a restart gives a poison event five more tries, which is fine. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

    public EventDispatcher(Db db, List<EventConsumer> consumers, DefaultOrchestrator orchestrator) {
        this.db = db;
        this.consumers = List.copyOf(consumers);
        this.orchestrator = orchestrator;
    }

    /** Claims and dispatches one batch. @return events claimed (a full batch means more may be waiting) */
    public int dispatchOnce() {
        var runsToStart = new ArrayList<Long>();
        int claimed = db.inTx(c -> {
            var events = claim(c);
            for (var e : events) {
                int failed = failures.getOrDefault(e.id(), 0);
                if (failed >= MAX_FAILURES) {
                    markDispatched(c, e.id());
                    failures.remove(e.id());
                    LOG.error("event {} ({}) failed {} times; marked dispatched without running consumers. "
                            + "Its intents will not start.", e.id(), e.name(), failed);
                    continue;
                }
                var savepoint = c.setSavepoint();
                try {
                    var started = new ArrayList<Long>();
                    for (var consumer : consumers) {
                        if (!consumer.accepts(e.name())) continue;
                        for (var intent : consumer.handle(c, e)) {
                            orchestrator.enqueue(c, intent).ifPresent(run -> started.add(run.id()));
                        }
                    }
                    markDispatched(c, e.id());
                    c.releaseSavepoint(savepoint);
                    runsToStart.addAll(started);
                    failures.remove(e.id());
                } catch (SQLException | RuntimeException ex) {
                    c.rollback(savepoint);
                    int n = failures.merge(e.id(), 1, Integer::sum);
                    LOG.warn("event {} ({}) failed attempt {}/{}: {}", e.id(), e.name(), n, MAX_FAILURES, ex.toString());
                }
            }
            return events.size();
        });
        // After the commit: the runs exist for certain, and a rollback can no longer undo them.
        for (var runId : runsToStart) {
            try {
                orchestrator.runIfDue(runId);
            } catch (RuntimeException ex) {
                // The run stays live and the cascade tick picks it up.
                LOG.warn("run {} could not start at once; the tick will retry it: {}", runId, ex.toString());
            }
        }
        return claimed;
    }

    private static List<Event> claim(Connection c) throws SQLException {
        var out = new ArrayList<Event>();
        try (var ps = Sql.prepare(c, """
                SELECT e.id, e.identity_id, e.name, e.source, e.occurred_at,
                       ARRAY(SELECT key   FROM jsonb_each_text(e.props) ORDER BY key) AS prop_keys,
                       ARRAY(SELECT value FROM jsonb_each_text(e.props) ORDER BY key) AS prop_values
                  FROM events e
                 WHERE e.dispatched_at IS NULL
                 ORDER BY e.occurred_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED""", BATCH);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var keys = (String[]) rs.getArray("prop_keys").getArray();
                var values = (String[]) rs.getArray("prop_values").getArray();
                var props = new HashMap<String, String>();
                for (int i = 0; i < keys.length; i++) if (values[i] != null) props.put(keys[i], values[i]);
                out.add(new Event(rs.getLong("id"), rs.getObject("identity_id", UUID.class), rs.getString("name"),
                        rs.getString("source"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), props));
            }
        }
        return out;
    }

    private static void markDispatched(Connection c, long eventId) throws SQLException {
        Sql.update(c, "UPDATE events SET dispatched_at = now() WHERE id = ?", eventId);
    }
}
