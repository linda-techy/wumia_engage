package in.brand.engage.ingest;

import io.micronaut.runtime.Micronaut;

/**
 * Engage ingest API — Phase 1.
 *
 * <p>Receives Shopify and Razorpay webhooks, verifies their signatures against
 * the raw body, stores them in the webhook inbox, and processes them into the
 * identity graph, checkouts, orders, payment attempts and events.
 *
 * <p>Local run: {@code ./gradlew :ingest-api:run} (reads config/local.env).
 */
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
