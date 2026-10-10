package in.brand.engage.orchestrator;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;

/** Single-step cascades over the real push templates. */
@Factory
class TestCascades {

    static final String BROWSE = "test_browse";
    static final String RESTOCK = "test_restock";

    @Singleton
    CascadeDefinition browse() {
        return new CascadeDefinition(BROWSE, Priority.LOW_INTENT,
                List.of(CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24))));
    }

    @Singleton
    CascadeDefinition restock() {
        return new CascadeDefinition(RESTOCK, Priority.HIGH_INTENT,
                List.of(CascadeDefinition.Step.push("push_back_in_stock_v1", Duration.ofHours(1)).urgent()
                        .staleDevicesAllowed()));
    }

    /* ---------------- multi-step cascades for CascadeRunnerTest (P4-T07) ---------------- */

    static final String TWO_STEP = "test_two_step";
    static final String GUARDED = "test_guarded";
    static final String TRACKING = "test_tracking";
    static final String IOS = "test_ios";

    /** Push, then push again 4 h later unless clicked. */
    @Singleton
    CascadeDefinition twoStep() {
        return new CascadeDefinition(TWO_STEP, Priority.LOW_INTENT, List.of(
                CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24)).waitAfter(Duration.ofHours(4)),
                // A different template: the same one twice would meet its own one-day cooldown.
                CascadeDefinition.Step.push("push_campaign_new_arrivals_v1", Duration.ofHours(24))));
    }

    /** A step that needs WhatsApp marketing consent (subscribers have none), then push. */
    @Singleton
    CascadeDefinition guarded() {
        return new CascadeDefinition(GUARDED, Priority.LOW_INTENT, List.of(
                CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24))
                        .require(Guard.WA_MARKETING).waitAfter(Duration.ofHours(4)),
                CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24))));
    }

    /** Transactional: WhatsApp unless known INCAPABLE, then SMS a day later. */
    @Singleton
    CascadeDefinition tracking() {
        return new CascadeDefinition(TRACKING, Priority.TRANSACTIONAL, List.of(
                CascadeDefinition.Step.whatsapp("order_shipped_v1").require(Guard.WA_CAPABLE_OR_UNKNOWN)
                        .waitAfter(Duration.ofHours(24)),
                CascadeDefinition.Step.sms("sms_order_shipped"))).succeedsOn("read", "delivered");
    }

    /** Push only where a push can be shown. */
    @Singleton
    CascadeDefinition ios() {
        return new CascadeDefinition(IOS, Priority.LOW_INTENT, List.of(
                CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24)).require(Guard.HAS_LIVE_DEVICE)));
    }
}
