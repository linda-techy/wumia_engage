package in.brand.engage.policy;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Clock;

/** The clock decisions are made by. Tests replace it with {@code @Replaces(Clock.class)}. */
@Factory
public class PolicyClock {

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
