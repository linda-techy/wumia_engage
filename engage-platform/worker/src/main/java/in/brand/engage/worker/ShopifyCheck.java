package in.brand.engage.worker;

import in.brand.engage.worker.inbox.OrderLookup;
import in.brand.engage.worker.shopify.ShopifyAdmin;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code worker shopify-check [WUM1001]}: read-only. Proves the Admin token
 * works, prints the newest order numbers and dates (no customer details) so
 * the number format is visible, and tries the inbox look-up for one number.
 */
final class ShopifyCheck {

    private ShopifyCheck() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].equals("shopify-check");
    }

    static void run(String[] args) {
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            var admin = ctx.getBean(ShopifyAdmin.class);
            var root = admin.graphql("""
                    { shop { name } orders(first: 5, reverse: true, sortKey: CREATED_AT) { nodes { name createdAt displayFulfillmentStatus } } }""",
                    Map.of(), Duration.ofSeconds(10));
            if (root.get("errors") instanceof List<?> errors && !errors.isEmpty()) System.out.println("Shopify reported: " + errors);
            if (root.get("data") instanceof Map<?, ?> data) {
                if (data.get("shop") instanceof Map<?, ?> shop) System.out.println("token works for: " + shop.get("name"));
                if (data.get("orders") instanceof Map<?, ?> orders && orders.get("nodes") instanceof List<?> nodes) {
                    System.out.println("newest orders the app can read (last 60 days):");
                    for (var n : nodes) {
                        if (n instanceof Map<?, ?> o) System.out.println("  " + o.get("name") + "  " + o.get("createdAt") + "  " + o.get("displayFulfillmentStatus"));
                    }
                    if (nodes.isEmpty()) System.out.println("  (none)");
                }
            }
            if (args.length > 1) {
                var found = ctx.getBean(OrderLookup.class).byName(args[1].replace("#", "").toUpperCase(java.util.Locale.ROOT));
                System.out.println("look-up " + args[1] + ": " + (found.isPresent()
                        ? "found " + found.get().name() + " (cancelled " + found.get().cancelled() + ", fulfilments " + found.get().fulfillments().size() + ")"
                        : "not found"));
            }
        } catch (ShopifyAdmin.Failure e) {
            System.out.println("Shopify call failed: " + e.getMessage());
        }
    }
}
