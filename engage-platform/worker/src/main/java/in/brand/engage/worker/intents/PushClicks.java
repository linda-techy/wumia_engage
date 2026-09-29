package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * A push click is the push channel's only success signal (CLAUDE.md §3.3).
 * ingest-api records it on the send and emits {@code push_clicked}; this
 * marks the send's cascade run succeeded, which stops any remaining steps.
 */
@Singleton
public class PushClicks implements EventConsumer {

    private final DefaultOrchestrator orchestrator;

    public PushClicks(DefaultOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("push_clicked");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var runId = e.props().get("cascade_run_id");
        if (runId != null) orchestrator.onSignal(c, Long.parseLong(runId), "clicked");
        return List.of();
    }
}
