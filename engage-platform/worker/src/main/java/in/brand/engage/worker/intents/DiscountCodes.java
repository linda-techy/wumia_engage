package in.brand.engage.worker.intents;

import java.time.Instant;

/** Creates discount codes in the store. {@link ShopifyDiscountCodes} in production; tests fake it. */
public interface DiscountCodes {

    /**
     * One percentage-off code for the whole order.
     *
     * @param minOrderPaise 0 = no minimum
     */
    record Spec(String code, String title, int percent, long minOrderPaise, Instant startsAt, Instant endsAt) {}

    /** @throws Failed Shopify refused it; {@link Failed#taken()} when the code already exists */
    void create(Spec spec) throws Failed;

    final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean taken;

        public Failed(String message, boolean taken) {
            super(message);
            this.taken = taken;
        }

        public boolean taken() {
            return taken;
        }
    }
}
