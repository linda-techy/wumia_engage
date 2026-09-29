package in.brand.engage.worker;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A settable clock; starts at the real time so tests that ignore it are unaffected. */
@Singleton
@Replaces(Clock.class)
public class TestClock extends Clock {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private volatile Instant now = Instant.now();

    public void setIst(String localDateTime) {
        now = LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return IST; }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
}
