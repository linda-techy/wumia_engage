package in.brand.engage.ingest.push;

import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request limits for the public storefront endpoints, keyed by
 * what the browser sends (its anon id). In memory, so per instance: enough to
 * stop one script from flooding the funnel or the device table, not a quota.
 */
@Singleton
public class RateLimiter {

    /** Bounds memory against a flood of distinct keys; the next window starts clean. */
    private static final int MAX_KEYS = 100_000;

    private record Window(long startMillis, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** @return true when this call is within {@code limit} per {@code per}. */
    public boolean allow(String bucket, String key, int limit, Duration per) {
        if (windows.size() > MAX_KEYS) windows.clear();
        long now = System.currentTimeMillis();
        long length = per.toMillis();
        var w = windows.compute(bucket + ':' + key, (k, cur) ->
                cur == null || now - cur.startMillis() >= length ? new Window(now, 1) : new Window(cur.startMillis(), cur.count() + 1));
        return w.count() <= limit;
    }

    public static final class Limited extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Limited() {
            super("rate limited");
        }
    }
}
