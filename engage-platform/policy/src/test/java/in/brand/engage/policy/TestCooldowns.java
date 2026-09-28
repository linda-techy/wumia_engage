package in.brand.engage.policy;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;

/** Stands in for the orchestrator's template registry: one template has a 6 h cooldown. */
@Singleton
@Replaces(TemplateCooldowns.None.class)
public class TestCooldowns implements TemplateCooldowns {

    static final String KEY = "test_push_cooldown";

    @Override
    public Optional<Duration> cooldown(String templateKey) {
        return KEY.equals(templateKey) ? Optional.of(Duration.ofHours(6)) : Optional.empty();
    }
}
