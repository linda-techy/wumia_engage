package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.CapabilityService;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.DeliveryOutcome;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T05: the capability tests of CLAUDE.md §10, plus concurrent same-day strikes. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CapabilityServiceTest {

    static final String MSISDN = "919876543210";
    static final Instant DAY1 = Instant.parse("2026-10-01T06:30:00Z");   // 12:00 IST

    @Inject CapabilityService capability;
    @Inject Db db;

    UUID id;

    @BeforeEach void identity() {
        id = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id));
    }

    void observe(DeliveryOutcome o) {
        db.inTx(c -> {
            capability.observe(c, id, Channel.WHATSAPP, o);
            return null;
        });
    }

    static DeliveryOutcome utility131026(Instant at) {
        return DeliveryOutcome.failed(131026, Category.UTILITY, MSISDN, at);
    }

    String row(String columns) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT " + columns + " FROM channel_capability WHERE identity_id = ? AND channel = 'whatsapp'", id);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test void auth_template_131026_to_91_number_records_no_strike() {
        observe(DeliveryOutcome.failed(131026, Category.AUTHENTICATION, MSISDN, DAY1));
        assertNull(row("state"), "no row at all: nothing was learned");
    }

    @Test void utility_template_131026_records_one_strike() {
        observe(utility131026(DAY1));
        assertEquals("UNKNOWN|{2026-10-01}", row("state || '|' || strike_days::text"));
    }

    @Test void two_strikes_on_different_days_marks_incapable() {
        observe(utility131026(DAY1));
        observe(utility131026(DAY1.plus(2, ChronoUnit.DAYS)));
        assertEquals("INCAPABLE", row("state"));
        assertEquals("120", row("round(extract(epoch FROM recheck_after - decided_at) / 86400)::int::text"),
                "rechecked after 120 days, not written off");
    }

    @Test void two_failures_same_day_count_as_one_strike() {
        observe(utility131026(DAY1));
        observe(utility131026(DAY1.plus(5, ChronoUnit.HOURS)));                 // 17:00 IST, same day
        assertEquals("UNKNOWN|{2026-10-01}", row("state || '|' || strike_days::text"));

        // The day is the IST calendar day: 23:30 and 00:30 IST are two days, though one UTC date.
        observe(utility131026(Instant.parse("2026-10-03T18:00:00Z")));          // 23:30 IST on the 3rd
        assertEquals("INCAPABLE", row("state"));
    }

    @Test void successful_delivery_promotes_to_capable_immediately() {
        observe(utility131026(DAY1));
        observe(DeliveryOutcome.of(DeliveryOutcome.Type.DELIVERED, DAY1.plusSeconds(60)));
        assertEquals("CAPABLE|{}", row("state || '|' || strike_days::text"));

        observe(utility131026(DAY1.plus(1, ChronoUnit.DAYS)));
        observe(utility131026(DAY1.plus(2, ChronoUnit.DAYS)));
        assertEquals("CAPABLE", row("state"), "CAPABLE is sticky");
    }

    @Test void incapable_expires_after_120_days_to_unknown() {
        observe(utility131026(DAY1));
        observe(utility131026(DAY1.plus(1, ChronoUnit.DAYS)));
        assertEquals("INCAPABLE", row("state"));

        db.inTx(c -> capability.expireIncapable(c, DAY1.plus(100, ChronoUnit.DAYS)));
        assertEquals("INCAPABLE", row("state"), "not yet");
        db.inTx(c -> capability.expireIncapable(c, DAY1.plus(122, ChronoUnit.DAYS)));
        assertEquals("UNKNOWN|{}", row("state || '|' || strike_days::text"));
    }

    @Test void error_131049_does_not_change_capability() {
        observe(utility131026(DAY1));
        observe(DeliveryOutcome.failed(131049, Category.MARKETING, MSISDN, DAY1.plus(1, ChronoUnit.DAYS)));
        assertEquals("UNKNOWN|{2026-10-01}", row("state || '|' || strike_days::text"), "no strike, no state change");
        assertEquals("2026-10-03 12:00", row("to_char(backoff_until AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI')"),
                "24 h after the 131049");
    }

    @Test void two_concurrent_131026_for_the_same_person_on_the_same_day_are_one_strike() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                int n = i;
                pool.submit(() -> {
                    start.await();
                    observe(utility131026(DAY1.plusSeconds(n)));
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("UNKNOWN|{2026-10-01}", row("state || '|' || strike_days::text"));
    }
}
