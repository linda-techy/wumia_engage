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

    /**
     * Runs before {@link #handle}, outside any transaction: the place for a
     * provider call (the Shopify Admin API) that must not pin a database
     * connection while it waits. Throwing retries the item with backoff,
     * exactly as a failure in {@code handle} does.
     */
    default void prepare(InboxRepository.Item item) {}
}
