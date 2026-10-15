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
 * SMS delivery reports as cascade signals (P4-T06, CLAUDE.md §3.3): an SMS
 * has no read receipt, so {@code sms_delivered} is its success. ingest-api
 * records the report and emits the event; this applies it to the run that
 * sent the message, where the definition counts {@code delivered}.
 */
@Singleton
public class SmsSignals implements EventConsumer {

    private final DefaultOrchestrator orchestrator;

    public SmsSignals(DefaultOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("sms_delivered");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var sendId = e.props().get("send_id");
        if (sendId != null) orchestrator.onSendSignal(c, Long.parseLong(sendId), "delivered");
        return List.of();
    }
}
