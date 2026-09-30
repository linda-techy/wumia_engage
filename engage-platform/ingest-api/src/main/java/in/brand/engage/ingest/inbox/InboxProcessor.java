package in.brand.engage.ingest.inbox;

import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.persistence.Db;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the webhook inbox. Runs on every pod; SKIP LOCKED means no leader
 * election. Each item is processed in its own transaction together with its
 * "processed" mark, so an item is either fully applied or retried.
 */
@Singleton
public class InboxProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(InboxProcessor.class);
    private static final int BATCH = 100;

    private final InboxRepository inbox;
    private final Db db;
    private final Map<String, InboxHandler> handlers;
    private final IngestMetrics metrics;

    public InboxProcessor(InboxRepository inbox, Db db, List<InboxHandler> handlers, IngestMetrics metrics) {
        this.inbox = inbox;
        this.db = db;
        this.metrics = metrics;
        this.handlers = handlers.stream().collect(Collectors.toMap(InboxHandler::source, Function.identity()));
    }

    @Scheduled(fixedDelay = "2s", initialDelay = "5s")
    void tick() {
        List<InboxRepository.Item> batch;
        do {
            batch = inbox.claim(BATCH);
            batch.forEach(this::processOne);
        } while (batch.size() == BATCH);   // drain a backlog instead of waiting 2s per 100
    }

    /** Public for tests: process everything currently due, synchronously. */
    public void drain() {
        tick();
    }

    private void processOne(InboxRepository.Item item) {
        var handler = handlers.get(item.source());
        if (handler == null) {
            failed(item, "no handler for source " + item.source());
            return;
        }
        long start = System.nanoTime();
        try {
            handler.prepare(item);
            db.inTx(c -> {
                handler.handle(c, item);
                inbox.markProcessed(c, item);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("inbox {}:{} ({}) failed on attempt {}: {}",
                    item.source(), item.deliveryId(), item.topic(), item.attempts(), e.getMessage());
            failed(item, e.getMessage());
        } finally {
            metrics.handled(item.source(), item.topic(), Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void failed(InboxRepository.Item item, String error) {
        inbox.markFailed(item, error);
        if (item.attempts() >= InboxRepository.MAX_ATTEMPTS) {
            // Alert on this line: the item will not be retried again (engage_inbox_dead_letters).
            LOG.error("inbox dead letter: source={} topic={} delivery_id={} last_error={}",
                    item.source(), item.topic(), item.deliveryId(), error);
        }
    }
}
