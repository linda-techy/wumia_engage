package in.brand.engage.worker;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code engage_push_tokens{state,browser}} (P2-T08), from the
 * {@code device_health} view (V11): active, stale (30 days without a refresh,
 * still sent to for back-in-stock) and inactive. Refreshed every minute; a
 * combination that disappears is dropped rather than left at its last value.
 */
@Singleton
public class PushTokenGauge {

    private static final Logger LOG = LoggerFactory.getLogger(PushTokenGauge.class);

    private final Db db;
    private final MultiGauge tokens;

    public PushTokenGauge(Db db, MeterRegistry registry) {
        this.db = db;
        this.tokens = MultiGauge.builder("engage.push.tokens")
                .description("Push devices by state (device_health) and browser")
                .register(registry);
    }

    @Scheduled(fixedDelay = "60s", initialDelay = "10s")
    public void refresh() {
        try {
            var rows = db.inTx(c -> {
                var out = new ArrayList<MultiGauge.Row<?>>();
                try (var ps = Sql.prepare(c, """
                        SELECT state, COALESCE(browser, 'unknown') AS browser, count(*) AS n
                          FROM device_health GROUP BY 1, 2""");
                     var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(MultiGauge.Row.of(Tags.of("state", rs.getString("state"), "browser", rs.getString("browser")),
                                rs.getLong("n")));
                    }
                }
                return out;
            });
            tokens.register(rows, true);
        } catch (RuntimeException e) {
            LOG.error("push token gauge refresh failed", e);
        }
    }
}
