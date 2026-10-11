package in.brand.engage.policy;

import in.brand.engage.core.messaging.Channel;
import java.util.Objects;
import java.util.UUID;

/**
 * One question to the policy engine: may this template go to this person on
 * this channel, now?
 *
 * @param journeyKey          the journey or intent asking; null for one-offs. It is
 *                            also the journey holdout experiment.
 * @param isReply             a response inside a service window the customer opened
 *                            (e.g. the STOP confirmation, P4)
 * @param allowStaleDevices   push: include tokens not refreshed within
 *                            {@code push.campaign_stale_days} (back-in-stock: the
 *                            shopper asked for it)
 * @param cascadeRunId        the cascade run sending, for the collision check; null outside a cascade
 */
public record DecisionRequest(UUID identityId, Channel channel, String templateKey, String journeyKey,
                              boolean isReply, boolean allowStaleDevices, Long cascadeRunId) {

    public DecisionRequest(UUID identityId, Channel channel, String templateKey, String journeyKey,
                           boolean isReply, boolean allowStaleDevices) {
        this(identityId, channel, templateKey, journeyKey, isReply, allowStaleDevices, null);
    }

    public DecisionRequest {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(templateKey, "templateKey");
    }

    public static DecisionRequest of(UUID identityId, Channel channel, String templateKey) {
        return new DecisionRequest(identityId, channel, templateKey, null, false, false);
    }

    public DecisionRequest forJourney(String key) {
        return new DecisionRequest(identityId, channel, templateKey, key, isReply, allowStaleDevices, cascadeRunId);
    }

    public DecisionRequest asReply() {
        return new DecisionRequest(identityId, channel, templateKey, journeyKey, true, allowStaleDevices, cascadeRunId);
    }

    public DecisionRequest allowingStaleDevices() {
        return new DecisionRequest(identityId, channel, templateKey, journeyKey, isReply, true, cascadeRunId);
    }

    public DecisionRequest inRun(Long runId) {
        return new DecisionRequest(identityId, channel, templateKey, journeyKey, isReply, allowStaleDevices, runId);
    }
}
