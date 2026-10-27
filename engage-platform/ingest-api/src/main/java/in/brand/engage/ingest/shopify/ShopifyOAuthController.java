package in.brand.engage.ingest.shopify;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.ShopifyTokens;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shopify's authorization step (V51): the store owner opens
 * https://engage.wumika.com/shopify/oauth/start while logged in to the store
 * admin, approves, and Shopify calls back with a one-time code that Engage
 * exchanges for the store's offline Admin token. Only the configured store is
 * accepted; the callback is checked by Shopify's HMAC and a one-time state.
 */
@Controller("/shopify/oauth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ShopifyOAuthController {

    private static final Logger LOG = LoggerFactory.getLogger(ShopifyOAuthController.class);

    /** The app's scopes (shopify.app.engage.toml): with Shopify-managed install a request may not ask for more. */
    static final String SCOPES = "read_orders,read_customers,read_inventory,read_products,write_pixels,read_customer_events,"
            + "read_fulfillments,write_discounts";
    static final Pattern SHOP = Pattern.compile("[a-z0-9][a-z0-9-]*\\.myshopify\\.com");

    private final Db db;
    private final ShopifyCodeExchange exchange;
    private final String shopDomain;
    private final String clientId;
    private final String apiSecret;
    private final String publicUrl;
    private final Clock clock;

    @Inject
    public ShopifyOAuthController(Db db, ShopifyCodeExchange exchange,
                                  @Value("${engage.shopify.shop-domain:}") String shopDomain,
                                  @Value("${engage.shopify.client-id:}") String clientId,
                                  @Value("${engage.shopify.api-secret:}") String apiSecret,
                                  @Value("${ENGAGE_PUBLIC_URL:`https://engage.wumika.com`}") String publicUrl) {
        this(db, exchange, shopDomain, clientId, apiSecret, publicUrl, Clock.systemUTC());
    }

    ShopifyOAuthController(Db db, ShopifyCodeExchange exchange, String shopDomain, String clientId, String apiSecret,
                           String publicUrl, Clock clock) {
        this.db = db;
        this.exchange = exchange;
        this.shopDomain = shopDomain == null ? "" : shopDomain.strip().toLowerCase(Locale.ROOT);
        this.clientId = clientId;
        this.apiSecret = apiSecret;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    String redirectUri() {
        return publicUrl + "/shopify/oauth/callback";
    }

    /** Sends the owner to Shopify's approval screen for this store. */
    @Get("/start")
    public HttpResponse<?> start() {
        if (!SHOP.matcher(shopDomain).matches() || blank(clientId) || blank(apiSecret)) {
            return page(503, "Engage is not set up for Shopify yet (SHOPIFY_SHOP_DOMAIN, SHOPIFY_CLIENT_ID, SHOPIFY_API_SECRET).");
        }
        var state = db.inTx(ShopifyTokens::newState);
        var url = "https://" + shopDomain + "/admin/oauth/authorize?client_id=" + enc(clientId) + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirectUri()) + "&state=" + enc(state);
        // 302 and no-store: a browser must never replay a cached redirect with a used state (Micronaut's redirect() is a 301).
        return HttpResponse.status(io.micronaut.http.HttpStatus.FOUND).header("Location", URI.create(url).toString())
                .header("Cache-Control", "no-store");
    }

    /** Shopify returns here with ?code, shop, state, timestamp, hmac. */
    @Get("/callback")
    public HttpResponse<?> callback(HttpRequest<?> request) {
        var params = new TreeMap<String, String>();
        request.getParameters().forEach((k, v) -> params.put(k, v.isEmpty() ? "" : v.getFirst()));
        var shop = params.getOrDefault("shop", "").toLowerCase(Locale.ROOT);
        if (!SHOP.matcher(shop).matches() || !shop.equals(shopDomain)) {
            LOG.warn("Shopify authorization refused: callback for another shop ({})", shop);
            return page(400, "This link is for a different Shopify store.");
        }
        if (!validHmac(params, apiSecret)) {
            LOG.warn("Shopify authorization refused: bad HMAC on the callback");
            return page(400, "That approval link could not be verified. Please start again.");
        }
        if (!db.inTx(c -> ShopifyTokens.consumeState(c, params.get("state"), clock.instant()))) {
            LOG.warn("Shopify authorization refused: unknown, used or expired state");
            return page(400, "That approval link has expired or was already used. Please start again.");
        }
        var code = params.get("code");
        if (blank(code)) return page(400, "Shopify did not send an approval code. Please start again.");

        ShopifyCodeExchange.Grant grant;
        try {
            grant = exchange.exchange(shop, code);
        } catch (ShopifyCodeExchange.Failed e) {
            LOG.warn("Shopify authorization: code exchange failed: {}", e.getMessage());
            return page(502, "Shopify did not complete the approval. Please try again in a minute.");
        }
        if (!grant.scopes().contains("read_orders")) {
            LOG.warn("Shopify authorization: granted scopes lack read_orders ({})", grant.scopes());
            return page(400, "Shopify granted access without reading orders. Please approve all the permissions Engage asks for.");
        }
        db.inTx(c -> {
            ShopifyTokens.save(c, shop, grant.token(), grant.scopes(), apiSecret);
            return null;
        });
        LOG.info("Shopify Admin access granted for {} (scopes {})", shop, grant.scopes());
        return page(200, "Engage is connected to your Shopify store. WhatsApp order look-ups and back-in-stock alerts now work. You can close this tab.");
    }

    /** Shopify's OAuth HMAC: every parameter but hmac, sorted, as key=value joined by &, HMAC-SHA256 hex. */
    static boolean validHmac(TreeMap<String, String> params, String secret) {
        var presented = params.get("hmac");
        if (blank(presented) || blank(secret)) return false;
        var message = new StringBuilder();
        params.forEach((k, v) -> {
            if (k.equals("hmac") || k.equals("signature")) return;
            if (!message.isEmpty()) message.append('&');
            message.append(k).append('=').append(v);
        });
        return Hmacs.constantTimeEquals(Hmacs.sha256Hex(secret, message.toString().getBytes(StandardCharsets.UTF_8)),
                presented.toLowerCase(Locale.ROOT));
    }

    private static HttpResponse<?> page(int status, String text) {
        var html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>WUMIKA Engage</title><body style=\"font:16px system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem\">"
                + "<h1 style=\"font-size:1.25rem\">WUMIKA Engage</h1><p>" + text + "</p></body>";
        return HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(status)).body(html).contentType(MediaType.TEXT_HTML_TYPE);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
