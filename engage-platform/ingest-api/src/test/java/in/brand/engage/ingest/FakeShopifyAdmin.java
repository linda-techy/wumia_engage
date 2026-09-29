package in.brand.engage.ingest;

import in.brand.engage.ingest.shopify.ShopifyAdmin;
import in.brand.engage.ingest.shopify.ShopifyAdminClient;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for the Shopify Admin API: a map from inventory item to variant, and a call counter. */
@Singleton
@Replaces(ShopifyAdminClient.class)
public class FakeShopifyAdmin implements ShopifyAdmin {

    final Map<String, Optional<VariantRef>> items = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean failing;

    @Override
    public Optional<VariantRef> variantOfInventoryItem(String inventoryItemId) {
        calls.incrementAndGet();
        if (failing) throw new IllegalStateException("Shopify Admin API errors: THROTTLED");
        var ref = items.get(inventoryItemId);
        if (ref == null) throw new IllegalStateException("fake: unknown item " + inventoryItemId);
        return ref;
    }
}
