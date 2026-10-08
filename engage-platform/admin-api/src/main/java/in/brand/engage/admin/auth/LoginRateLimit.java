package in.brand.engage.admin.auth;

import jakarta.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in attempts per client IP: 30 per 10 minutes. The per-account lock
 * (5 failures, 15 min) stops guessing one password; this stops one source
 * trying many accounts. In memory: per instance, which is enough for one
 * console process (a second instance needs a shared store).
 */
@Singleton
public class LoginRateLimit {

    static final int MAX = 30;
    static final long WINDOW_MS = 10 * 60 * 1000L;
    private static final int MAX_KEYS = 50_000;

    private record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String ip) {
        if (windows.size() > MAX_KEYS) windows.clear();
        long now = System.currentTimeMillis();
        var w = windows.compute(ip == null ? "?" : ip, (k, cur) ->
                cur == null || now - cur.start() >= WINDOW_MS ? new Window(now, 1) : new Window(cur.start(), cur.count() + 1));
        return w.count() <= MAX;
    }

    /** Tests only: one test class signs in more than 30 times. */
    public void reset() {
        windows.clear();
    }
}
