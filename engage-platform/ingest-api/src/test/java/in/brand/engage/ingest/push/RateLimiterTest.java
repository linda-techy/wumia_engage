package in.brand.engage.ingest.push;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T06: the storefront limits hold across ingest pods. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class RateLimiterTest {

    @Inject Db db;

    @Test void two_pods_share_one_count() {
        var podA = new RateLimiter(db);
        var podB = new RateLimiter(db);
        var key = UUID.randomUUID().toString();
        assertTrue(podA.allow("test", key, 3, Duration.ofHours(1)));
        assertTrue(podB.allow("test", key, 3, Duration.ofHours(1)));
        assertTrue(podA.allow("test", key, 3, Duration.ofHours(1)));
        assertFalse(podB.allow("test", key, 3, Duration.ofHours(1)), "the fourth call is over the limit, whichever pod takes it");
        assertTrue(podA.allow("test", UUID.randomUUID().toString(), 3, Duration.ofHours(1)), "another browser has its own count");
        assertTrue(podA.allow("other", key, 3, Duration.ofHours(1)), "another endpoint has its own count");
    }

    @Test void a_long_key_is_hashed_not_stored_whole() {
        var limiter = new RateLimiter(db);
        var key = "k".repeat(5000);
        assertTrue(limiter.allow("test", key, 1, Duration.ofMinutes(1)));
        assertFalse(limiter.allow("test", key, 1, Duration.ofMinutes(1)));
        assertTrue(RateLimiter.shorten(key).startsWith("sha256:"));
        assertEquals(71, RateLimiter.shorten(key).length());
    }
}
