package in.brand.engage.ingest.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * ingest-api's own metrics (P1-T05), scraped at {@code /prometheus}.
 *
 * <table>
 * <tr><th>Metric</th><th>Alert</th></tr>
 * <tr><td>engage_inbox_pending</td><td>&gt; 1,000 for 5 min</td></tr>
 * <tr><td>engage_inbox_oldest_pending_seconds</td><td>&gt; 300</td></tr>
 * <tr><td>engage_inbox_dead_letters</td><td>&gt; 0</td></tr>
 * <tr><td>engage_webhook_received_total{source,topic,result}</td><td>401 rate &gt; 1%</td></tr>
 * <tr><td>engage_handler_seconds{source,topic}</td><td>p99 &gt; 2 s</td></tr>
 * </table>
 *
 * <p>Label values are bounded: Shopify's topic header is not covered by the
 * HMAC, so anything that does not look like a topic, and every rejected
 * request, is counted as {@code other}.
 */
@Singleton
public class IngestMetrics {

    private static final Pattern TOPIC = Pattern.compile("[a-z_]{1,40}(/[a-z_]{1,40})?|[a-z_]{1,40}\\.[a-z_.]{1,40}");

    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("engage.inbox.pending", pending, AtomicLong::get)
                .description("Webhook inbox rows not yet processed (dead letters excluded)").register(registry);
        Gauge.builder("engage.inbox.oldest.pending.seconds", oldestPendingSeconds, AtomicLong::get)
                .description("Age of the oldest pending inbox row").register(registry);
        Gauge.builder("engage.inbox.dead.letters", deadLetters, AtomicLong::get)
                .description("Inbox rows that failed 10 times and wait for a human").register(registry);
    }

    public enum Result { STORED, DUPLICATE, FILTERED, UNAUTHORIZED, BAD_REQUEST }

    public void received(String source, String topic, Result result) {
        var verified = result != Result.UNAUTHORIZED;
        Counter.builder("engage.webhook.received")
                .description("Webhook deliveries by outcome")
                .tag("source", source)
                .tag("topic", verified ? topic(topic) : "other")
                .tag("result", result.name().toLowerCase())
                .register(registry).increment();
    }

    public void handled(String source, String topic, Duration took) {
        Timer.builder("engage.handler")
                .description("Inbox handler time per item")
                .tag("source", source)
                .tag("topic", topic(topic))
                .publishPercentileHistogram()
                .register(registry).record(took);
    }

    /** Set by the inbox housekeeping job from the database. */
    public void inbox(long pendingRows, long oldestSeconds, long deadRows) {
        pending.set(pendingRows);
        oldestPendingSeconds.set(oldestSeconds);
        deadLetters.set(deadRows);
    }

    static String topic(String t) {
        return t != null && TOPIC.matcher(t).matches() ? t : "other";
    }
}
