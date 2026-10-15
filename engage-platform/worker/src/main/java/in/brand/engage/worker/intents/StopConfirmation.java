package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * {@code wa_stop} (P4-T04, phase-4 §3): the one confirmation after a STOP,
 * as a free-form reply inside the 24 h window the STOP itself opened. Free,
 * and it keeps people from reaching for "Block", which is what hurts the
 * number's quality rating. Policy lets a reply through without an opt-in
 * (the STOP just withdrew it); the subject is the STOP message, so a
 * replayed STOP never confirms twice.
 */
@Singleton
public class StopConfirmation implements EventConsumer {

    public static final String INTENT = "wa_stop_confirm";

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition stopConfirm() {
            return new CascadeDefinition(INTENT, Priority.TRANSACTIONAL,
                    List.of(CascadeDefinition.Step.whatsappReply("wa_stop_confirm")));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("wa_stop");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) {
        var messageId = e.props().get("message_id");
        if (e.identityId() == null || messageId == null) return List.of();
        return List.of(new MessageIntent(e.identityId(), INTENT, "stop:" + messageId, Map.of(), null));
    }
}
