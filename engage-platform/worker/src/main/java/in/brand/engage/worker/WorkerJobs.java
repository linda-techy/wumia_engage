package in.brand.engage.worker;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event dispatch loop and the cascade tick. Several workers may run all of
 * them at once: every claim is {@code FOR UPDATE SKIP LOCKED} or a lease.
 * {@code engage.worker.jobs-enabled=false} turns them off (tests drive them by hand).
 */
@Singleton
@Requires(property = "engage.worker.jobs-enabled", value = "true", defaultValue = "true")
public class WorkerJobs {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerJobs.class);

    private final EventDispatcher events;
    private final DefaultOrchestrator orchestrator;

    public WorkerJobs(EventDispatcher events, DefaultOrchestrator orchestrator) {
        this.events = events;
        this.orchestrator = orchestrator;
    }

    /** Every second; drains while batches come back full. */
    @Scheduled(fixedDelay = "1s", initialDelay = "5s")
    void dispatchEvents() {
        try {
            while (events.dispatchOnce() == EventDispatcher.BATCH) {
                // more waiting
            }
        } catch (RuntimeException e) {
            LOG.error("event dispatch pass failed", e);
        }
    }

    /** Steps that came due: deferrals, delayed intents. */
    @Scheduled(fixedDelay = "5s", initialDelay = "10s")
    void tickCascades() {
        try {
            while (orchestrator.tick(200) == 200) {
                // more due
            }
        } catch (RuntimeException e) {
            LOG.error("cascade tick failed", e);
        }
    }
}
