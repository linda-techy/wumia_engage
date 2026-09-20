package in.brand.engage.ingest.inbox;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Processes one inbox item inside a transaction the processor owns. Handlers
 * must be idempotent and order-independent: providers retry, and two pods can
 * process a shopper's checkout and order in either order.
 */
public interface InboxHandler {

    /** e.g. "shopify", "razorpay" */
    String source();

    void handle(Connection c, InboxRepository.Item item) throws SQLException;
}
