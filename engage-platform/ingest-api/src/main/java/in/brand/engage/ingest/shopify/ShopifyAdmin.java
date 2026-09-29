package in.brand.engage.ingest.shopify;

import java.util.Optional;

/**
 * The few Shopify Admin API reads ingest-api needs. Called only from
 * {@code InboxHandler.prepare}, outside any database transaction. Tests fake it.
 */
public interface ShopifyAdmin {

    /**
     * Numeric ids, as webhooks and cart lines carry them, plus what a
     * back-in-stock push needs to name the item.
     *
     * @param variantTitle "M", "M / Blue", or "Default Title" for a one-size product
     */
    record VariantRef(String variantId, String productId, String productTitle, String productHandle,
                      String variantTitle) {

        public VariantRef(String variantId, String productId) {
            this(variantId, productId, null, null, null);
        }
    }

    /**
     * The variant an inventory item stocks.
     *
     * @return empty when Shopify has no such item or it stocks no variant (deleted)
     * @throws RuntimeException on any API failure, including throttling: the inbox retries with backoff
     */
    Optional<VariantRef> variantOfInventoryItem(String inventoryItemId);
}
