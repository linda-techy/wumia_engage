package in.brand.engage.ingest.shopify;

import java.util.Optional;

/**
 * The few Shopify Admin API reads ingest-api needs. Called only from
 * {@code InboxHandler.prepare}, outside any database transaction. Tests fake it.
 */
public interface ShopifyAdmin {

    /** Numeric ids, as webhooks and cart lines carry them. */
    record VariantRef(String variantId, String productId) {}

    /**
     * The variant an inventory item stocks.
     *
     * @return empty when Shopify has no such item or it stocks no variant (deleted)
     * @throws RuntimeException on any API failure, including throttling: the inbox retries with backoff
     */
    Optional<VariantRef> variantOfInventoryItem(String inventoryItemId);
}
