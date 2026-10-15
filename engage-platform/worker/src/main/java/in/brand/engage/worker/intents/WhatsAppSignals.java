package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * WhatsApp statuses and replies, turned into cascade signals (P4-T07,
 * CLAUDE.md §3.3 and §4.4). ingest-api records them and emits events; this
 * applies them to the run that sent the message.
 *
 * <ul>
 * <li>{@code wa_read}: success for cascades that count a read (transactional
 *     updates); ignored by promotional ones, which need a click or a reply.</li>
 * <li>{@code wa_message_received} replying to one of our messages
 *     ({@code context_wamid}), or a quick-reply button tap: {@code replied}.
 *     For NDR and address confirmation that is the only success.</li>
 * <li>{@code wa_failed} with 131026 or 131049: the run's next step (SMS, push)
 *     is brought forward to now instead of waiting out the timer.</li>
 * </ul>
 */
@Singleton
public class WhatsAppSignals implements EventConsumer {

    static final Set<String> ESCALATE = Set.of("131026", "131049");
    /** ingest-api emits a button tap under its payload's name (MetaInboxHandler.QUICK_REPLIES). */
    static final Set<String> REPLIES = Set.of("wa_message_received", "wa_optin_yes", "ndr_reschedule", "fit_ok",
            "review_love", "review_help");

    private final DefaultOrchestrator orchestrator;

    public WhatsAppSignals(DefaultOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("wa_read") || eventName.equals("wa_failed") || REPLIES.contains(eventName);
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        switch (e.name()) {
            case "wa_read" -> {
                var sendId = e.props().get("send_id");
                if (sendId != null) orchestrator.onSendSignal(c, Long.parseLong(sendId), "read");
            }
            case "wa_failed" -> {
                var sendId = e.props().get("send_id");
                if (sendId != null && ESCALATE.contains(e.props().get("code"))) orchestrator.escalate(c, Long.parseLong(sendId));
            }
            default -> {
                var context = e.props().get("context_wamid");
                if (context == null) break;
                try (var ps = Sql.prepare(c, "SELECT id FROM sends WHERE provider_id = ? AND channel = 'whatsapp'", context);
                     var rs = ps.executeQuery()) {
                    if (rs.next()) orchestrator.onSendSignal(c, rs.getLong(1), "replied");
                }
            }
        }
        return List.of();
    }
}
