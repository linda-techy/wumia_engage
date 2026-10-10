package in.brand.engage.worker.intents;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code discountCodeBasicCreate} on the Shopify Admin GraphQL API. Needs the
 * {@code write_discounts} scope on the store's app; without it Shopify answers
 * ACCESS_DENIED and the discount step is skipped (the reminders still go).
 *
 * <p>Token: the same client-credentials grant as ingest-api's
 * {@code ShopifyAdminClient} (the app's client id and secret for a 24 h Admin
 * token, cached and renewed), or {@code SHOPIFY_ADMIN_TOKEN} when set.
 */
@Singleton
public class ShopifyDiscountCodes implements DiscountCodes {

    static final String MUTATION = """
            mutation($d: DiscountCodeBasicInput!) {
              discountCodeBasicCreate(basicCodeDiscount: $d) {
                codeDiscountNode { id }
                userErrors { field message code }
              }
            }""";
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

    public ShopifyDiscountCodes(JsonMapper json,
                                @Value("${SHOPIFY_SHOP_DOMAIN:}") String shopDomain,
                                @Value("${SHOPIFY_CLIENT_ID:}") String clientId,
                                @Value("${SHOPIFY_API_SECRET:}") String clientSecret,
                                @Value("${SHOPIFY_ADMIN_TOKEN:}") String fixedToken,
                                @Value("${SHOPIFY_ADMIN_API_VERSION:2026-07}") String apiVersion,
                                Clock clock) {
        this.json = json;
        this.baseUrl = "https://" + shopDomain;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.fixedToken = fixedToken;
        this.apiVersion = apiVersion;
        this.clock = clock;
    }

    @Override
    public void create(Spec spec) throws Failed {
        Map<String, Object> root = call(Map.of("query", MUTATION, "variables", Map.of("d", input(spec))));
        if (root.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            var text = String.valueOf(errors);
            throw new Failed(text.contains("ACCESS_DENIED") ? "access denied: the app needs write_discounts" : text, false);
        }
        if (root.get("data") instanceof Map<?, ?> data && data.get("discountCodeBasicCreate") instanceof Map<?, ?> result) {
            if (result.get("userErrors") instanceof List<?> ue && !ue.isEmpty()) {
                boolean taken = ue.stream().anyMatch(e -> e instanceof Map<?, ?> m && "TAKEN".equals(m.get("code")));
                throw new Failed("Shopify refused the code: " + ue, taken);
            }
            if (result.get("codeDiscountNode") instanceof Map<?, ?>) return;
        }
        throw new Failed("unexpected Shopify response: " + root, false);
    }

    /** The mutation's input, exposed for tests. */
    static Map<String, Object> input(Spec spec) {
        var d = new LinkedHashMap<String, Object>();
        d.put("title", spec.title());
        d.put("code", spec.code());
        d.put("startsAt", spec.startsAt().toString());
        d.put("endsAt", spec.endsAt().toString());
        d.put("context", Map.of("all", "ALL"));
        d.put("customerGets", Map.of(
                "value", Map.of("percentage", BigDecimal.valueOf(spec.percent()).movePointLeft(2)),
                "items", Map.of("all", true)));
        d.put("usageLimit", 1);                 // single use: a leaked code is worth one order
        d.put("appliesOncePerCustomer", true);
        if (spec.minOrderPaise() > 0) {
            d.put("minimumRequirement", Map.of("subtotal", Map.of("greaterThanOrEqualToSubtotal",
                    BigDecimal.valueOf(spec.minOrderPaise()).movePointLeft(2).toPlainString())));
        }
        d.put("combinesWith", Map.of("orderDiscounts", false, "productDiscounts", false, "shippingDiscounts", true));
        return d;
    }

    private Map<String, Object> call(Object body) throws Failed {
        HttpResponse<byte[]> res = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/api/" + apiVersion + "/graphql.json"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Shopify-Access-Token", accessToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                .build());
        if (res.statusCode() == 401) {
            token = null;
            throw new Failed("Shopify Admin API 401: access token rejected", false);
        }
        if (res.statusCode() != 200) throw new Failed("Shopify Admin API " + res.statusCode(), false);
        return read(res.body());
    }

    private String accessToken() throws Failed {
        if (fixedToken != null && !fixedToken.isBlank()) return fixedToken;
        var t = token;
        if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
        synchronized (this) {
            t = token;
            if (t != null && clock.instant().isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new Failed("SHOPIFY_CLIENT_ID and SHOPIFY_API_SECRET are needed to create discount codes", false);
            }
            var form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
            var res = send(HttpRequest.newBuilder(URI.create(baseUrl + "/admin/oauth/access_token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build());
            if (res.statusCode() != 200) throw new Failed("Shopify token request " + res.statusCode(), false);
            var node = read(res.body());
            if (!(node.get("access_token") instanceof String value)) throw new Failed("Shopify token response has no access_token", false);
            long seconds = node.get("expires_in") instanceof Number n ? n.longValue() : 86_399;
            token = t = new Token(value, clock.instant().plusSeconds(seconds));
            return t.value();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Failed {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new Failed("Shopify call failed: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted calling Shopify", false);
        }
    }

    private byte[] bytes(Object value) throws Failed {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new Failed("cannot write the request: " + e.getMessage(), false);
        }
    }

    private Map<String, Object> read(byte[] body) throws Failed {
        try {
            return json.readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (IOException | RuntimeException e) {
            throw new Failed("unreadable Shopify response", false);
        }
    }
}
