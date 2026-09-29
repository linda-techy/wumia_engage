package in.brand.engage.orchestrator;

import java.util.UUID;

/**
 * The only door (CLAUDE.md §3.1). Journeys, campaigns and webhooks call
 * {@link #dispatch}; nothing else reaches a channel.
 */
public interface MessageOrchestrator {

    /**
     * Starts the intent's cascade, or returns the live one: idempotent on
     * (intentKey, subjectKey). Runs the first step now if it is due.
     */
    CascadeRun dispatch(MessageIntent intent);

    /** Terminates live cascades for a subject. Called on conversion. */
    void cancel(UUID identityId, String subjectKey, String reason);
}
