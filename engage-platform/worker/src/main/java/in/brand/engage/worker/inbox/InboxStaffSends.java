package in.brand.engage.worker.inbox;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * What staff send from the inbox (admin-api writes the event; CLAUDE.md §1.1:
 * the console never calls WhatsApp itself):
 * <ul>
 * <li>{@code inbox_staff_reply}: their typed reply, a free-form service message
 *     that policy lets through only inside the customer's 24 h window;</li>
 * <li>{@code inbox_followup}: the approved utility template after the window,
 *     business-initiated, so policy requires the customer's WhatsApp opt-in;</li>
 * <li>{@code inbox_order_help} (V53): a chat about one of the customer's orders,
 *     to someone who has not written in 24 h or ever; opt-in required likewise.</li>
 * </ul>
 * Whatever policy or WhatsApp refuses appears in the thread with the reason
 * (SendRepository.logInboxRefusal).
 */
@Singleton
public class InboxStaffSends implements EventConsumer {

    public static final String REPLY = "inbox_staff_reply";
    public static final String FOLLOWUP = "inbox_followup";
    public static final String ORDER_HELP = "inbox_order_help";
    static final String FOLLOWUP_TEMPLATE = "support_followup_v1";
    static final String ORDER_HELP_TEMPLATE = "support_order_v1";

    @Factory
    static class Definitions {
        @Bean
        @Singleton
        CascadeDefinition inboxStaffReply() {
            return new CascadeDefinition(REPLY, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsappReply(REPLY)));
        }

        @Bean
        @Singleton
        CascadeDefinition inboxFollowup() {
            return new CascadeDefinition(FOLLOWUP, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsapp(FOLLOWUP_TEMPLATE)));
        }

        @Bean
        @Singleton
        CascadeDefinition inboxOrderHelp() {
            return new CascadeDefinition(ORDER_HELP, Priority.TRANSACTIONAL, List.of(CascadeDefinition.Step.whatsapp(ORDER_HELP_TEMPLATE)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return Set.of(REPLY, FOLLOWUP, ORDER_HELP).contains(eventName);
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        if (e.identityId() == null) return List.of();
        var vars = new HashMap<String, String>();
        var operator = e.props().get("operator_id");
        if (operator != null) vars.put("operator_id", operator);
        if (REPLY.equals(e.name())) {
            var text = e.props().get("text");
            if (text == null || text.isBlank()) return List.of();
            vars.put("text", text);
            return List.of(new MessageIntent(e.identityId(), REPLY, "staff:" + e.id(), vars, null));
        }
        vars.put("name", InboxAutoReply.firstName(c, e.identityId()));
        if (ORDER_HELP.equals(e.name())) {
            var order = e.props().get("order_number");
            if (order == null || order.isBlank()) return List.of();
            vars.put("order_number", order);
            return List.of(new MessageIntent(e.identityId(), ORDER_HELP, "order_help:" + e.id(), vars, null));
        }
        return List.of(new MessageIntent(e.identityId(), FOLLOWUP, "followup:" + e.id(), vars, null));
    }
}
