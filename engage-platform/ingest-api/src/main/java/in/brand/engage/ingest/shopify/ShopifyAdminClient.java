package in.brand.engage.ingest.shopify;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.ShopifyTokens;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Shopify Admin GraphQL (needs read_inventory and read_products). One small
 * query per inventory item ever seen: the mapping is cached in
 * {@code inventory_state}, so a restock storm after a warehouse sync costs one
 * lookup per item, not per update.
 *
 * <p><b>Token</b>, the first that exists:
 * <ol>
 * <li>{@code SHOPIFY_ADMIN_TOKEN}, if set;</li>
 * <li>the store's offline token from Shopify's authorization step (V51,
 *     /shopify/oauth/start), for a store outside the app's organization (the live store);</li>
 * <li>the client credentials grant: the app's client ID and secret for a
 *     24-hour token, which Shopify allows only for a store in the app's own
 *     Dev Dashboard organization (the dev store). Cached, renewed before it
 *     expires or after a 401.</li>
 * </ol>
 */
@Singleton
public class ShopifyAdminClient implements ShopifyAdmin {

    private static final String QUERY =
            "query($id: ID!) { inventoryItem(id: $id) { variant { id title product { id title handle } } } }";
    /** Renew this long before expiry, so a request never carries a token about to lapse. */
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(5);

    private record Token(String value, Instant expiresAt) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String fixedToken;
    private final String apiVersion;
    private final Clock clock;
    private final ShopifyTokens.Cached stored;
    private volatile Token token;

    @Inject
    public ShopifyAdminClient(JsonMapper json, Db db,
                              @Value("${engage.shopify.shop-domain:}") String shopDomain,
                              @Value("${engage.shopify.client-id:}") String clientId,
                              @Value("${engage.shopify.api-secret:}") String clientSecret,
                              @Value("${SHOPIFY_ADMIN_TOKEN:}") String fixedToken,
                              @Value("${SHOPIFY_ADMIN_API_VERSION:2026-07}") String apiVersion) {
        this(json, "https://" + shopDomain, clientId, clientSecret, fixedToken, apiVersion, Clock.systemUTC(),
                new ShopifyTokens.Cached(db, shopDomain, clientSecret));
    }

    ShopifyAdminClient(JsonMapper json, String baseUrl, String clientId, String clientSecret, String fixedToken,
                       String apiVersion, Clock clock) {
        this(json, baseUrl, clientId, clientSecret, fixedToken, apiVersion, clock, null);
    }

    ShopifyAdminClient(JsonMapper json, String baseUrl, String clientId, String clientSecret, String fixedToken,
                       String apiVersion, Clock clock, ShopifyTokens.Cached stored) {
        this.stored = stored;
        this.json = json;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.fixedToken = fixedToken;
        this.apiVersion = apiVersion;
        this.clock = clock;
    }

    @Override
    public Optional<VariantRef> variantOfInventoryItem(String inventoryItemId) {
        var body = bytes(Map.of("query", QUERY,
                "variables", Map.of("id", "gid://shopify/InventoryItem/" + inventoryItemId)));
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/api/" + apiVersion + "/graphql.json"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Shopify-Access-Token", accessToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
        if (response.statusCode() == 401) {
            token = null;                     // revoked or rotated: the retry fetches a fresh one
            if (stored != null) stored.invalidate();
            throw new IllegalStateException("Shopify Admin API 401: access token rejected (if Engage's approval was revoked, "
                    + "approve it again at /shopify/oauth/start)");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Shopify Admin API " + response.statusCode() + ": "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return parse(read(response.body()));
    }

    /** The current Admin token: the configured one, the owner-approved one, or a cached client-credentials token. */
    String accessToken() {
        if (fixedToken != null && !fixedToken.isBlank()) return fixedToken;
        var approved = stored == null ? null : stored.get(clock.instant());
        if (approved != null) return approved;
        var t = token;
        if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
        synchronized (this) {
            t = token;
            if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
            token = t = requestToken();
            return t.value();
        }
    }

    private Token requestToken() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            // Fail, not skip: the inbox keeps the update and retries once configured.
            throw new IllegalStateException("SHOPIFY_CLIENT_ID and SHOPIFY_API_SECRET are needed for Admin API access");
        }
        var form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/oauth/access_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        var bodyText = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() != 200) {
            // shop_not_permitted: the store is not in the app's Dev Dashboard organization; approve Engage instead.
            throw new IllegalStateException("Shopify token request " + response.statusCode()
                    + (bodyText.contains("shop_not_permitted") ? ": shop_not_permitted (approve Engage at /shopify/oauth/start)" : ": " + bodyText));
        }
        var node = read(response.body());
        var value = node.get("access_token");
        var expiresIn = node.get("expires_in");
        if (value == null || value.isNull()) throw new IllegalStateException("Shopify token response has no access_token");
        long seconds = expiresIn == null || expiresIn.isNull() ? 86_399 : expiresIn.getLongValue();
        return new Token(value.getStringValue(), clock.instant().plusSeconds(seconds));
    }

    Optional<VariantRef> parse(JsonNode root) {
        var errors = root.get("errors");
        if (errors != null && !errors.isNull()) {
            // THROTTLED arrives here too: the inbox backoff is the retry.
            throw new IllegalStateException("Shopify Admin API errors: " + text(errors));
        }
        var item = path(root, "data", "inventoryItem");
        var variant = item == null ? null : path(item, "variant");
        if (variant == null) return Optional.empty();
        var product = path(variant, "product");
        return Optional.of(new VariantRef(numericId(variant.get("id").getStringValue()),
                product == null ? null : numericId(product.get("id").getStringValue()),
                product == null ? null : string(product, "title"),
                product == null ? null : string(product, "handle"),
                string(variant, "title")));
    }

    private String text(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (IOException e) {
            return String.valueOf(node);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Shopify call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling Shopify", e);
        }
    }

    private byte[] bytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(byte[] body) {
        try {
            return json.readValue(body, JsonNode.class);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable Shopify response: " + e.getMessage(), e);
        }
    }

    private static JsonNode path(JsonNode node, String... keys) {
        var n = node;
        for (var k : keys) {
            if (n == null || n.isNull()) return null;
            n = n.get(k);
        }
        return n == null || n.isNull() ? null : n;
    }

    private static String string(JsonNode node, String key) {
        var v = node.get(key);
        return v == null || v.isNull() ? null : v.getStringValue();
    }

    /** gid://shopify/ProductVariant/123 → 123 */
    static String numericId(String gid) {
        return gid.substring(gid.lastIndexOf('/') + 1);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
