package in.brand.engage.policy;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves config into a {@link ConfigSnapshot}, cached, and records each
 * distinct snapshot in {@code config_snapshots}.
 *
 * <p>The cache is dropped within about a second of any config change: a
 * trigger on {@code config_versions} (V8) sends {@code NOTIFY config_changed}
 * and a dedicated connection here {@code LISTEN}s for it. The connection is not
 * from the pool, because a pooled connection is handed to someone else between
 * uses and would miss notifications. It reconnects with backoff, and drops the
 * whole cache on every (re)connect, because notifications sent while it was
 * down are lost. A 30 s expiry backs this up and also picks up versions whose
 * {@code effective_from} arrives without any insert.
 *
 * <p>Kill switches do not come from here: see {@link KillSwitch}.
 */
@Singleton
public class ConfigResolver implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigResolver.class);
    private static final Duration TTL = Duration.ofSeconds(30);

    /** Keys the engine reads. Each needs a default or a '*' version, or startup fails. */
    static final List<String> REQUIRED = List.of(
            "quiet_hours", "journey.enabled",
            "halt.channel", "halt.marketing", "halt.journey",
            "cap.whatsapp.marketing.1d", "cap.whatsapp.marketing.7d",
            "cap.push.marketing.1d", "cap.email.marketing.7d",
            "budget.whatsapp.marketing.daily_paise",
            "rate.whatsapp.marketing_paise", "rate.whatsapp.utility_paise",
            "holdout.global_pct", "holdout.journey_pct",
            "push.campaign_stale_days");

    private record Cached(ConfigSnapshot snapshot, long loadedAtNanos) {}

    private final Db db;
    private final List<String> requiredKeys;
    private final String url, user, password;
    private final AtomicLong generation = new AtomicLong();
    private volatile Cached cached;
    private volatile boolean running;
    private volatile boolean listening;
    private Thread listener;

    @Inject
    public ConfigResolver(Db db,
                          @Value("${datasources.default.url:}") String url,
                          @Value("${datasources.default.username:}") String user,
                          @Value("${datasources.default.password:}") String password) {
        this.db = db;
        this.requiredKeys = REQUIRED;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** No listener: for checking a key list in tests. */
    ConfigResolver(Db db, List<String> requiredKeys) {
        this.db = db;
        this.requiredKeys = requiredKeys;
        this.url = "";
        this.user = "";
        this.password = "";
    }

    @PostConstruct
    void start() {
        verifyRequiredKeys();
        if (url.isBlank()) {
            LOG.warn("datasources.default.url is not set: config cache relies on its {} expiry only", TTL);
            return;
        }
        running = true;
        listener = Thread.ofPlatform().daemon().name("config-changed-listener").start(this::listenLoop);
    }

    public ConfigSnapshot snapshot() {
        var c = cached;
        if (c != null && System.nanoTime() - c.loadedAtNanos() < TTL.toNanos()) return c.snapshot();
        long gen = generation.get();
        var loaded = new Cached(load(), System.nanoTime());
        // Only cache it if nothing changed while loading; otherwise it may already be stale.
        if (generation.get() == gen) cached = loaded;
        return loaded.snapshot();
    }

    public void invalidate() {
        generation.incrementAndGet();
        cached = null;
    }

    /** True while the LISTEN connection is up. */
    boolean listening() {
        return listening;
    }

    void verifyRequiredKeys() {
        var missing = db.inTx(c -> {
            var out = new ArrayList<String>();
            try (var ps = Sql.prepare(c, """
                    SELECT r.key FROM unnest(CAST(? AS text[])) AS r(key)
                     WHERE NOT EXISTS (SELECT 1 FROM config_keys k
                                        WHERE k.key = r.key AND k.default_value IS NOT NULL)
                       AND NOT EXISTS (SELECT 1 FROM config_current v
                                        WHERE v.key = r.key AND v.selector = '*')
                     ORDER BY 1""", (Object) requiredKeys.toArray(String[]::new));
                 var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("config keys with neither a default nor a '*' version: " + missing
                    + ". Add a default_value in a migration, or a config version in the admin console.");
        }
    }

    private ConfigSnapshot load() {
        return db.inTx(c -> {
            // One statement: the rows and the snapshot row come from the same view of config.
            try (var ps = c.prepareStatement(SqlFiles.get("config_snapshot.sql"));
                 var rs = ps.executeQuery()) {
                long id = 0;
                var values = new HashMap<String, String>();
                while (rs.next()) {
                    id = rs.getLong("snapshot_id");
                    if (rs.getString("key") != null) values.put(rs.getString("key") + "|" + rs.getString("selector"), rs.getString("val"));
                }
                if (id == 0) throw new IllegalStateException("config_snapshots insert returned no id");
                return new ConfigSnapshot(id, values);
            }
        });
    }

    private void listenLoop() {
        long backoffMs = 1_000;
        while (running) {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                try (var st = c.createStatement()) {
                    st.execute("LISTEN config_changed");
                }
                invalidate();
                listening = true;
                backoffMs = 1_000;
                var pg = c.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications(1_000);
                    if (notifications != null && notifications.length > 0) invalidate();
                }
            } catch (SQLException e) {
                if (!running) return;
                LOG.warn("config_changed listener lost its connection; retrying in {} ms: {}", backoffMs, e.getMessage());
            } finally {
                listening = false;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        if (listener != null) listener.interrupt();
    }
}
