package in.brand.engage.worker.inbox;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Staff alerts every 10 s. Off with the other worker jobs in tests. (Auto-replies are event consumers.) */
@Singleton
@Requires(property = "engage.worker.jobs-enabled", value = "true", defaultValue = "true")
public class InboxJobs {

    private static final Logger LOG = LoggerFactory.getLogger(InboxJobs.class);

    private final StaffAlerts alerts;

    public InboxJobs(StaffAlerts alerts) {
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = "10s", initialDelay = "25s")
    void staffAlerts() {
        try {
            alerts.runOnce();
        } catch (RuntimeException e) {
            LOG.error("staff alert pass failed", e);
        }
    }
}
