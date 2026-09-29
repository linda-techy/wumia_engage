package in.brand.engage.worker;

import in.brand.engage.orchestrator.SendRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fails {@code queued} sends older than 10 minutes as {@code lost_in_flight}:
 * a crash between the router's two transactions. Never re-sends them, because
 * the provider may have delivered (P3-T05).
 */
@Singleton
@Requires(property = "engage.worker.jobs-enabled", value = "true", defaultValue = "true")
public class QueuedSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(QueuedSweeper.class);

    private final SendRepository sends;

    public QueuedSweeper(SendRepository sends) {
        this.sends = sends;
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "30s")
    void sweep() {
        try {
            int n = sends.sweepLostInFlight();
            // Zero in steady state. Alert when this is non-zero.
            if (n > 0) LOG.error("{} sends lost in flight (queued > 10 min) marked failed", n);
        } catch (RuntimeException e) {
            LOG.error("queued-send sweep failed", e);
        }
    }
}
