package in.brand.engage.worker;

import in.brand.engage.orchestrator.MessageIntent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Turns an event into the intents it starts (P3-T07: back_in_stock,
 * price_drop, browse_abandon, cart_recovery). Never calls the router or a
 * channel.
 *
 * <p>{@link #handle} runs inside the transaction that claims the event, on
 * that transaction's connection: its reads and writes (e.g. marking waitlist
 * rows notified) commit with the event and the runs it starts, or not at all.
 * Throwing rolls all of it back and the event is retried.
 */
public interface EventConsumer {

    boolean accepts(String eventName);

    List<MessageIntent> handle(Connection c, Event event) throws SQLException;
}
