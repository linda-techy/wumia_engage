package in.brand.engage.ingest.shopify;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Shopify Admin GraphQL, authenticated with {@code SHOPIFY_ADMIN_TOKEN}
 * (needs read_inventory and read_products). One small query per inventory
 * item ever seen: the mapping is cached in {@code inventory_state}, so a
 * restock storm after a warehouse sync costs one lookup per item, not per update.
 */
@Singleton
public class ShopifyAdminClient implements ShopifyAdmin {

    private static final String QUERY =
            "query($id: ID!) { inventoryItem(id: $id) { variant { id product { id } } } }";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String shopDomain;
    private final String token;
    private final String apiVersion;

    public ShopifyAdminClient(JsonMapper json,
                              @Value("${engage.shopify.shop-domain:}") String shopDomain,
                              @Value("${SHOPIFY_ADMIN_TOKEN:}") String token,
                              @Value("${SHOPIFY_ADMIN_API_VERSION:2026-07}") String apiVersion) {
        this.json = json;
        this.shopDomain = shopDomain;
        this.token = token;
        this.apiVersion = apiVersion;
    }

    @Override
    public Optional<VariantRef> variantOfInventoryItem(String inventoryItemId) {
        if (token == null || token.isBlank()) {
            // Fail, not skip: the inbox keeps the update and retries it once the token is set.
            throw new IllegalStateException("SHOPIFY_ADMIN_TOKEN is not set: cannot resolve inventory item "
                    + inventoryItemId + " to a variant");
        }
        try {
            var body = json.writeValueAsBytes(Map.of("query", QUERY,
                    "variables", Map.of("id", "gid://shopify/InventoryItem/" + inventoryItemId)));
            var request = HttpRequest.newBuilder(
                            URI.create("https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Shopify-Access-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Shopify Admin API " + response.statusCode() + ": "
                        + new String(response.body(), StandardCharsets.UTF_8));
            }
            return parse(json.readValue(response.body(), JsonNode.class));
        } catch (IOException e) {
            throw new IllegalStateException("Shopify Admin API call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling the Shopify Admin API", e);
        }
    }

    static Optional<VariantRef> parse(JsonNode root) {
        var errors = root.get("errors");
        if (errors != null && !errors.isNull()) {
            // THROTTLED arrives here too: the inbox backoff is the retry.
            throw new IllegalStateException("Shopify Admin API errors: " + errors);
        }
        var item = path(root, "data", "inventoryItem");
        var variant = item == null ? null : path(item, "variant");
        if (variant == null) return Optional.empty();
        var product = path(variant, "product");
        return Optional.of(new VariantRef(numericId(variant.get("id").getStringValue()),
                product == null ? null : numericId(product.get("id").getStringValue())));
    }

    private static JsonNode path(JsonNode node, String... keys) {
        var n = node;
        for (var k : keys) {
            if (n == null || n.isNull()) return null;
            n = n.get(k);
        }
        return n == null || n.isNull() ? null : n;
    }

    /** gid://shopify/ProductVariant/123 → 123 */
    static String numericId(String gid) {
        return gid.substring(gid.lastIndexOf('/') + 1);
    }
}
