package in.brand.engage.orchestrator;

import in.brand.engage.core.messaging.Channel;
import in.brand.engage.policy.DecisionRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt on one channel, as the router receives it.
 *
 * @param idempotencyKey unique per attempt; a repeat returns {@link SendResult.Duplicate}
 * @param cascadeRunId   null for sends outside a cascade
 * @param reply          a reply inside the WhatsApp service window ({@link CascadeDefinition.Step#reply})
 */
public record SendCommand(UUID identityId, String intentKey, Long cascadeRunId, int stepIndex,
                          Channel channel, String templateKey, Map<String, String> vars,
                          String idempotencyKey, Duration ttl, boolean highUrgency,
                          boolean allowStaleDevices, boolean reply) {

    public SendCommand(UUID identityId, String intentKey, Long cascadeRunId, int stepIndex, Channel channel,
                       String templateKey, Map<String, String> vars, String idempotencyKey, Duration ttl,
                       boolean highUrgency, boolean allowStaleDevices) {
        this(identityId, intentKey, cascadeRunId, stepIndex, channel, templateKey, vars, idempotencyKey, ttl,
                highUrgency, allowStaleDevices, false);
    }

    public SendCommand {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(intentKey, "intentKey");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(ttl, "ttl");
        vars = Map.copyOf(vars);
    }

    DecisionRequest toDecisionRequest() {
        var req = DecisionRequest.of(identityId, channel, templateKey).forJourney(intentKey);
        if (reply) req = req.asReply();
        return allowStaleDevices ? req.allowingStaleDevices() : req;
    }
}
