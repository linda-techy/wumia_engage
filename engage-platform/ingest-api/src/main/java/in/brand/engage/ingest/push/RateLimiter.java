package in.brand.engage.ingest.push;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-window request limits for the public storefront endpoints, keyed by
 * what the browser sends (its anon id, client id, order id). Counted in
 * Postgres ({@code rate_windows}, V37) so the limit holds across ingest pods
 * (P7-T06). It stops one script from flooding the funnel or the device table;
 * it is not a quota, and a key the browser chooses can be changed, which is
 * why the tables it protects carry their own caps too (stock_waitlist ≤ 50).
 *
 * <p>If Postgres cannot be reached the call is allowed: the request that
 * follows needs the database anyway and will fail on its own.
 */
@Singleton
public class RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);
    private static final int MAX_KEY = 80;

    private final Db db;

    public RateLimiter(Db db) {
        this.db = db;
    }

    /** @return true when this call is within {@code limit} per {@code per}. */
    public boolean allow(String bucket, String key, int limit, Duration per) {
        long seconds = Math.max(1, per.toSeconds());
        var bucketKey = bucket + ':' + shorten(key == null ? "" : key);
        try {
            int hits = db.inTx(c -> {
                try (var ps = Sql.prepare(c, """
                        INSERT INTO rate_windows (bucket_key, window_start)
                        VALUES (?, to_timestamp(floor(extract(epoch FROM now()) / CAST(? AS bigint)) * CAST(? AS bigint)))
                        ON CONFLICT (bucket_key, window_start) DO UPDATE SET hits = rate_windows.hits + 1
                        RETURNING hits""", bucketKey, seconds, seconds);
                     var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            return hits <= limit;
        } catch (RuntimeException e) {
            LOG.warn("rate limit check failed for {}: allowing ({})", bucket, e.toString());
            return true;
        }
    }

    @Scheduled(cron = "0 17 * * * *")
    void purgeHourly() {
        try {
            purge();
        } catch (RuntimeException e) {
            LOG.warn("rate window purge failed: {}", e.toString());
        }
    }

    /** Windows that ended more than a day ago. */
    public int purge() {
        return db.inTx(c -> Sql.update(c, "DELETE FROM rate_windows WHERE window_start < now() - interval '1 day'"));
    }

    /** Keys are validated by each endpoint; a long one is hashed so the primary key stays small. */
    static String shorten(String key) {
        if (key.length() <= MAX_KEY) return key;
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Limited extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Limited() {
            super("rate limited");
        }
    }
}
