package in.brand.engage.orchestrator;

import in.brand.engage.core.messaging.Channel;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * An intent's ordered attempts. Definitions are code (reviewed, versioned);
 * register one as a bean and {@link CascadeDefinitions} finds it.
 *
 * <p>P3 runs single-step cascades. Guards, success signals and cancel events
 * arrive with the multi-step runner (P4-T06).
 */
public record CascadeDefinition(String intentKey, Priority priority, List<Step> steps) {

    public CascadeDefinition {
        Objects.requireNonNull(intentKey, "intentKey");
        Objects.requireNonNull(priority, "priority");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException(intentKey + " has no steps");
    }

    /**
     * @param ttl               how long the provider may hold it for an offline device
     * @param highUrgency       push Urgency high (back-in-stock); normal otherwise
     * @param allowStaleDevices push to tokens not refreshed lately (the shopper asked for this)
     * @param waitAfter         delay before the next step, from this step's send (CLAUDE.md §3.2)
     */
    public record Step(Channel channel, String templateKey, Duration ttl, boolean highUrgency,
                       boolean allowStaleDevices, Duration waitAfter) {

        public static Step push(String templateKey, Duration ttl) {
            return new Step(Channel.PUSH, templateKey, ttl, false, false, Duration.ZERO);
        }

        public Step urgent() {
            return new Step(channel, templateKey, ttl, true, allowStaleDevices, waitAfter);
        }

        public Step staleDevicesAllowed() {
            return new Step(channel, templateKey, ttl, highUrgency, true, waitAfter);
        }
    }
}
