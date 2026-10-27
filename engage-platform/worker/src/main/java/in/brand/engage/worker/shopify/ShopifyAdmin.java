package in.brand.engage.worker.shopify;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.ShopifyTokens;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
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
import java.util.List;
import java.util.Map;

/**
 * The Shopify Admin GraphQL API for the worker (discount codes, order look-ups
 * from the WhatsApp inbox). Token, the first that exists, as ingest-api's
 * {@code ShopifyAdminClient}: {@code SHOPIFY_ADMIN_TOKEN}; the store's offline
 * token from the owner's approval (V51); the client-credentials grant (only for
 * a store in the app's own organization, so the dev store, not the live one).
 */
@Singleton
public class ShopifyAdmin {

    /** The call failed as a whole: no token, HTTP error, GraphQL errors (ACCESS_DENIED for a missing scope). */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }

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
    public ShopifyAdmin(JsonMapper json, Db db,
                        @Value("${SHOPIFY_SHOP_DOMAIN:}") String shopDomain,
                        @Value("${SHOPIFY_CLIENT_ID:}") String clientId,
                        @Value("${SHOPIFY_API_SECRET:}") String clientSecret,
                        @Value("${SHOPIFY_ADMIN_TOKEN:}") String fixedToken,
                        @Value("${SHOPIFY_ADMIN_API_VERSION:2026-07}") String apiVersion,
                        Clock clock) {
        this(json, shopDomain, clientId, clientSecret, fixedToken, apiVersion, clock,
                new ShopifyTokens.Cached(db, shopDomain, clientSecret));
    }

    /** Without the stored token (the discount smoke test). */
    public ShopifyAdmin(JsonMapper json,
                        String shopDomain, String clientId, String clientSecret, String fixedToken, String apiVersion,
                        Clock clock) {
        this(json, shopDomain, clientId, clientSecret, fixedToken, apiVersion, clock, null);
    }

    private ShopifyAdmin(JsonMapper json, String shopDomain, String clientId, String clientSecret, String fixedToken,
                         String apiVersion, Clock clock, ShopifyTokens.Cached stored) {
        this.stored = stored;
        this.json = json;
        this.baseUrl = "https://" + shopDomain;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.fixedToken = fixedToken;
        this.apiVersion = apiVersion;
        this.clock = clock;
    }

    /**
     * One GraphQL request.
     *
     * @return the whole response ({@code data}, and {@code errors} when Shopify
     *         reports some alongside data); a response with errors and no data throws
     */
    public Map<String, Object> graphql(String query, Map<String, Object> variables, Duration timeout) throws Failure {
        var res = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/api/" + apiVersion + "/graphql.json"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Shopify-Access-Token", accessToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes(Map.of("query", query, "variables", variables))))
                .build());
        if (res.statusCode() == 401) {
            token = null;
            if (stored != null) stored.invalidate();
            throw new Failure("Shopify Admin API 401: access token rejected (approve Engage again at /shopify/oauth/start if it was revoked)");
        }
        if (res.statusCode() != 200) throw new Failure("Shopify Admin API " + res.statusCode());
        var root = read(res.body());
        if (root.get("errors") instanceof List<?> errors && !errors.isEmpty() && !(root.get("data") instanceof Map<?, ?>)) {
            var text = String.valueOf(errors);
            throw new Failure(text.contains("ACCESS_DENIED") ? "access denied (missing app scope): " + text : text);
        }
        return root;
    }

    private String accessToken() throws Failure {
        if (fixedToken != null && !fixedToken.isBlank()) return fixedToken;
        var approved = stored == null ? null : stored.get(clock.instant());
        if (approved != null) return approved;
        var t = token;
        if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
        synchronized (this) {
            t = token;
            if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new Failure("SHOPIFY_CLIENT_ID and SHOPIFY_API_SECRET are needed for the Shopify Admin API");
            }
            var form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
            var res = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/oauth/access_token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build());
            if (res.statusCode() != 200) {
                // 400 shop_not_permitted: the store is outside the app's organization, and nobody has approved Engage yet.
                throw new Failure("Shopify token request " + res.statusCode() + (res.statusCode() == 400
                        ? ": the store has not approved Engage yet (open https://engage.wumika.com/shopify/oauth/start)" : ""));
            }
            var node = read(res.body());
            if (!(node.get("access_token") instanceof String value)) throw new Failure("Shopify token response has no access_token");
            long seconds = node.get("expires_in") instanceof Number n ? n.longValue() : 86_399;
            token = t = new Token(value, clock.instant().plusSeconds(seconds));
            return t.value();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Failure {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new Failure("Shopify call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted calling Shopify");
        }
    }

    private byte[] bytes(Object value) throws Failure {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new Failure("cannot write the request: " + e.getMessage());
        }
    }

    private Map<String, Object> read(byte[] body) throws Failure {
        try {
            return json.readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (IOException | RuntimeException e) {
            throw new Failure("unreadable Shopify response");
        }
    }
}
