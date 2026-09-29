package in.brand.engage.orchestrator;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A settable clock. Tests pin IST wall-clock times with {@link #setIst}. */
@Singleton
@Replaces(Clock.class)
public class TestClock extends Clock {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private volatile Instant now = Instant.now();

    public void setIst(String localDateTime) {
        now = ist(localDateTime);
    }

    static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return IST; }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
}
