package in.brand.engage.policy;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;

/**
 * Per-template cooldowns (policy step 8). They are authored with the template
 * copy, so the template registry in {@code orchestrator} (P3-T03) supplies
 * them; policy cannot depend on it, hence this interface.
 */
public interface TemplateCooldowns {

    Optional<Duration> cooldown(String templateKey);

    /** Used only when no registry is on the classpath: no template has a cooldown. */
    @Singleton
    @Secondary
    final class None implements TemplateCooldowns {
        @Override
        public Optional<Duration> cooldown(String templateKey) {
            return Optional.empty();
        }
    }
}
