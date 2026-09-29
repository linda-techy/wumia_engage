package in.brand.engage.orchestrator;

import java.time.Instant;
import java.util.UUID;

/** A {@code cascade_runs} row (V4). Live while status is active or waiting. */
public record CascadeRun(long id, String intentKey, UUID identityId, String subjectKey, int priority,
                         int stepIndex, String status, String outcome, Instant nextStepAt, int deferrals) {

    public boolean live() {
        return "active".equals(status) || "waiting".equals(status);
    }
}
