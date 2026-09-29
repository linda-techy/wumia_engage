package in.brand.engage.ingest.shopify;

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
 * <p><b>Token.</b> The app and the dev store belong to the same Dev Dashboard
 * organization, so the app exchanges its own client ID and secret (already
 * configured for webhook signatures) for a 24-hour Admin token: the client
 * credentials grant. The token is cached and renewed shortly before it
 * expires, or after a 401. Nothing is copied by hand. {@code SHOPIFY_ADMIN_TOKEN},
 * if set, is used instead (a store outside the organization).
 */
@Singleton
public class ShopifyAdminClient implements ShopifyAdmin {

    private static final String QUERY =
            "query($id: ID!) { inventoryItem(id: $id) { variant { id product { id } } } }";
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
    private volatile Token token;

    @Inject
    public ShopifyAdminClient(JsonMapper json,
                              @Value("${engage.shopify.shop-domain:}") String shopDomain,
                              @Value("${engage.shopify.client-id:}") String clientId,
                              @Value("${engage.shopify.api-secret:}") String clientSecret,
                              @Value("${SHOPIFY_ADMIN_TOKEN:}") String fixedToken,
                              @Value("${SHOPIFY_ADMIN_API_VERSION:2026-07}") String apiVersion) {
        this(json, "https://" + shopDomain, clientId, clientSecret, fixedToken, apiVersion, Clock.systemUTC());
    }

    ShopifyAdminClient(JsonMapper json, String baseUrl, String clientId, String clientSecret, String fixedToken,
                       String apiVersion, Clock clock) {
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
            throw new IllegalStateException("Shopify Admin API 401: access token rejected");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Shopify Admin API " + response.statusCode() + ": "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return parse(read(response.body()));
    }

    /** The current Admin token: the configured one, or a cached client-credentials token. */
    String accessToken() {
        if (fixedToken != null && !fixedToken.isBlank()) return fixedToken;
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
            // shop_not_permitted: the store is not in the app's Dev Dashboard organization.
            throw new IllegalStateException("Shopify token request " + response.statusCode() + ": " + bodyText);
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
                product == null ? null : numericId(product.get("id").getStringValue())));
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

    /** gid://shopify/ProductVariant/123 → 123 */
    static String numericId(String gid) {
        return gid.substring(gid.lastIndexOf('/') + 1);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
