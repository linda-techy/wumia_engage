package in.brand.engage.worker;

import in.brand.engage.orchestrator.MessageRouter;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the router, and so every channel adapter, at boot. A missing or
 * unreadable {@code FIREBASE_SERVICE_ACCOUNT_FILE} then stops the worker from
 * starting, instead of failing the first push hours later.
 */
@Singleton
public class WorkerStartup implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerStartup.class);

    private final MessageRouter router;

    public WorkerStartup(MessageRouter router) {
        this.router = router;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("worker ready: channels {}", router.channels());
    }
}
