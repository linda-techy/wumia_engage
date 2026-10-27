package in.brand.engage.worker.inbox;

import in.brand.engage.worker.shopify.ShopifyAdmin;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An order looked up by its number (e.g. WUM1001) for a customer who typed it
 * in WhatsApp. Shopify is asked live: Engage only keeps orders from after the
 * store was connected, and only of released customers. Tests fake it.
 */
public interface OrderLookup {

    record Fulfillment(String status, String company, String number, String url, Instant deliveredAt) {}

    /** @param phones every phone on the order (order, shipping, billing), as Shopify has them */
    record ShopOrder(String name, String email, List<String> phones, boolean cancelled, List<Fulfillment> fulfillments) {}

    Optional<ShopOrder> byName(String name) throws ShopifyAdmin.Failure;

    /** Needs the app's read_orders scope (and read_fulfillments for tracking). */
    @Singleton
    class Shopify implements OrderLookup {

        static final String QUERY = """
                query($q: String!) {
                  orders(first: 3, query: $q) {
                    nodes {
                      name email phone cancelledAt
                      shippingAddress { phone }
                      billingAddress { phone }
                      fulfillments(first: 5) { displayStatus deliveredAt trackingInfo(first: 1) { company number url } }
                    }
                  }
                }""";

        private final ShopifyAdmin admin;

        public Shopify(ShopifyAdmin admin) {
            this.admin = admin;
        }

        @Override
        public Optional<ShopOrder> byName(String name) throws ShopifyAdmin.Failure {
            var root = admin.graphql(QUERY, Map.of("q", "name:" + name + " OR name:#" + name), Duration.ofSeconds(5));
            if (!(root.get("data") instanceof Map<?, ?> data && data.get("orders") instanceof Map<?, ?> orders
                    && orders.get("nodes") instanceof List<?> nodes)) {
                throw new ShopifyAdmin.Failure("unexpected orders response");
            }
            for (var n : nodes) {
                if (!(n instanceof Map<?, ?> o) || !InboxAutoReply.sameOrder(String.valueOf(o.get("name")), name)) continue;
                var phones = new ArrayList<String>();
                if (o.get("phone") instanceof String p) phones.add(p);
                for (var a : List.of("shippingAddress", "billingAddress")) {
                    if (o.get(a) instanceof Map<?, ?> addr && addr.get("phone") instanceof String p) phones.add(p);
                }
                var fulfillments = new ArrayList<Fulfillment>();
                if (o.get("fulfillments") instanceof List<?> fs) {
                    for (var f : fs) {
                        if (!(f instanceof Map<?, ?> fm)) continue;
                        String company = null, number = null, url = null;
                        if (fm.get("trackingInfo") instanceof List<?> ti && !ti.isEmpty() && ti.getFirst() instanceof Map<?, ?> t) {
                            company = t.get("company") instanceof String s ? s : null;
                            number = t.get("number") instanceof String s ? s : null;
                            url = t.get("url") instanceof String s ? s : null;
                        }
                        var delivered = fm.get("deliveredAt") instanceof String s ? Instant.parse(s) : null;
                        fulfillments.add(new Fulfillment(String.valueOf(fm.get("displayStatus")), company, number, url, delivered));
                    }
                }
                return Optional.of(new ShopOrder(String.valueOf(o.get("name")), o.get("email") instanceof String e ? e : null,
                        phones, o.get("cancelledAt") != null, fulfillments));
            }
            return Optional.empty();
        }
    }
}
