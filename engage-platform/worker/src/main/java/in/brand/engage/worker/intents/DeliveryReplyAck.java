package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * {@code delivery_reply}: a customer answered a failed-delivery message and
 * ingest-api opened a support request for staff. The customer gets one free
 * reply ({@code delivery_ack}) in the window their message opened, so they
 * know it reached someone. Once per request.
 */
@Singleton
public class DeliveryReplyAck implements EventConsumer {

    public static final String INTENT = "delivery_ack";

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition deliveryAck() {
            return new CascadeDefinition(INTENT, Priority.TRANSACTIONAL,
                    List.of(CascadeDefinition.Step.whatsappReply("delivery_ack")));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("delivery_reply");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var request = e.props().get("support_request_id");
        if (e.identityId() == null || request == null) return List.of();
        String name = null;
        try (var ps = Sql.prepare(c, "SELECT attrs->>'first_name' FROM profiles WHERE identity_id = ?", e.identityId());
             var rs = ps.executeQuery()) {
            if (rs.next()) name = rs.getString(1);
        }
        return List.of(new MessageIntent(e.identityId(), INTENT, "support:" + request,
                Map.of("name", CheckoutAbandon.firstName(name)), null));
    }
}
