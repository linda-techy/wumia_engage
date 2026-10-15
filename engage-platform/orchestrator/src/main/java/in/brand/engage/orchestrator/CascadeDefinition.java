package in.brand.engage.orchestrator;

import in.brand.engage.core.messaging.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An intent's ordered attempts (CLAUDE.md §3.2). Definitions are code
 * (reviewed, versioned); register one as a bean and {@link CascadeDefinitions}
 * finds it.
 *
 * @param successSignals what ends the whole cascade as {@code succeeded}
 *                       (CLAUDE.md §3.3): push {@code clicked}; WhatsApp
 *                       {@code read} for transactional runs, {@code clicked}
 *                       or {@code replied} for promotional ones; SMS
 *                       {@code delivered}. A signal not listed is ignored.
 */
public record CascadeDefinition(String intentKey, Priority priority, List<Step> steps, Set<String> successSignals) {

    /** The intent variable that picks one of a step's {@link Step#alternateTemplates}. */
    public static final String TEMPLATE_VAR = "template";

    /** Promotional defaults: a click or a reply is engagement; a read or a delivery is not. */
    public static final Set<String> PROMOTIONAL_SUCCESS = Set.of("clicked", "replied", "converted");

    public CascadeDefinition {
        Objects.requireNonNull(intentKey, "intentKey");
        Objects.requireNonNull(priority, "priority");
        steps = List.copyOf(steps);
        successSignals = Set.copyOf(successSignals);
        if (steps.isEmpty()) throw new IllegalArgumentException(intentKey + " has no steps");
    }

    public CascadeDefinition(String intentKey, Priority priority, List<Step> steps) {
        this(intentKey, priority, steps, PROMOTIONAL_SUCCESS);
    }

    /** The same cascade, ending on these signals instead. */
    public CascadeDefinition succeedsOn(String... signals) {
        return new CascadeDefinition(intentKey, priority, steps, Set.of(signals));
    }

    /**
     * @param ttl                how long the provider may hold it for an offline device
     * @param highUrgency        push Urgency high (back-in-stock); normal otherwise
     * @param allowStaleDevices  push to tokens not refreshed lately (the shopper asked for this)
     * @param waitAfter          delay before the next step, from this step's send (CLAUDE.md §3.2)
     * @param alternateTemplates other copy the intent may pick with {@code vars.template}, e.g. a
     *                           version without a size for one-size products. Anything not listed
     *                           here is ignored, so an intent can never send an arbitrary template.
     * @param reply              a reply inside the WhatsApp service window the customer just opened
     *                           (the STOP confirmation): policy lets it through without an opt-in
     * @param guards             all must pass, or the step is skipped at once (no wait consumed)
     * @param varsFrom           a {@link StepVars} provider asked for values at send time, or null
     */
    public record Step(Channel channel, String templateKey, Duration ttl, boolean highUrgency,
                       boolean allowStaleDevices, Duration waitAfter, List<String> alternateTemplates, boolean reply,
                       List<Guard> guards, String varsFrom) {

        public Step(Channel channel, String templateKey, Duration ttl, boolean highUrgency, boolean allowStaleDevices,
                    Duration waitAfter, List<String> alternateTemplates, boolean reply, List<Guard> guards) {
            this(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, alternateTemplates, reply, guards, null);
        }

        public Step {
            alternateTemplates = List.copyOf(alternateTemplates);
            guards = List.copyOf(guards);
        }

        public Step(Channel channel, String templateKey, Duration ttl, boolean highUrgency, boolean allowStaleDevices,
                    Duration waitAfter, List<String> alternateTemplates, boolean reply) {
            this(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, alternateTemplates, reply, List.of());
        }

        public Step(Channel channel, String templateKey, Duration ttl, boolean highUrgency, boolean allowStaleDevices,
                    Duration waitAfter, List<String> alternateTemplates) {
            this(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, alternateTemplates, false, List.of());
        }

        public static Step push(String templateKey, Duration ttl) {
            return new Step(Channel.PUSH, templateKey, ttl, false, false, Duration.ZERO, List.of());
        }

        public static Step whatsapp(String templateKey) {
            return new Step(Channel.WHATSAPP, templateKey, Duration.ofHours(24), false, false, Duration.ZERO, List.of());
        }

        /** Marketing email (P7-T01); skipped while no email adapter is configured. */
        public static Step email(String templateKey) {
            return new Step(Channel.EMAIL, templateKey, Duration.ofHours(72), false, false, Duration.ZERO, List.of());
        }

        public static Step sms(String templateKey) {
            return new Step(Channel.SMS, templateKey, Duration.ofHours(24), false, false, Duration.ZERO, List.of());
        }

        /** A free-form WhatsApp reply inside the window the customer opened. */
        public static Step whatsappReply(String templateKey) {
            return new Step(Channel.WHATSAPP, templateKey, Duration.ofHours(1), false, false, Duration.ZERO, List.of(), true);
        }

        public Step urgent() {
            return new Step(channel, templateKey, ttl, true, allowStaleDevices, waitAfter, alternateTemplates, reply, guards, varsFrom);
        }

        public Step staleDevicesAllowed() {
            return new Step(channel, templateKey, ttl, highUrgency, true, waitAfter, alternateTemplates, reply, guards, varsFrom);
        }

        public Step waitAfter(Duration wait) {
            return new Step(channel, templateKey, ttl, highUrgency, allowStaleDevices, wait, alternateTemplates, reply, guards, varsFrom);
        }

        public Step require(Guard... more) {
            var all = new ArrayList<>(guards);
            all.addAll(List.of(more));
            return new Step(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, alternateTemplates, reply, all, varsFrom);
        }

        public Step orTemplate(String alternate) {
            var all = new ArrayList<>(alternateTemplates);
            all.add(alternate);
            return new Step(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, all, reply, guards, varsFrom);
        }

        /** Ask the named {@link StepVars} provider for values just before this step sends. */
        public Step withVarsFrom(String provider) {
            return new Step(channel, templateKey, ttl, highUrgency, allowStaleDevices, waitAfter, alternateTemplates, reply,
                    guards, provider);
        }

        /** The first guard that fails for this person, or null when every guard passes. */
        public Guard firstFailing(Guard.Context context) {
            for (var g : guards) if (!g.passes(context)) return g;
            return null;
        }

        /** The template for these vars: a listed alternate if {@code vars.template} names one, else the step's own. */
        public String templateFor(Map<String, String> vars) {
            var picked = vars.get(TEMPLATE_VAR);
            return picked != null && alternateTemplates.contains(picked) ? picked : templateKey;
        }
    }
}
