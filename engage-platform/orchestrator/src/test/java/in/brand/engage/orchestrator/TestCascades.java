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
}
