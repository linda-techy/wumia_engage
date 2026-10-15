package in.brand.engage.worker.intents;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Links into the storefront. The storefront's own domain when configured
 * (what the shopper subscribed on; a push from another origin looks like spam
 * to Chrome), else the myshopify domain. The router adds UTMs later.
 */
@Singleton
public class Storefront {

    private final String base;

    public Storefront(@Value("${STOREFRONT_BASE_URL:}") String storefrontBaseUrl,
                      @Value("${SHOPIFY_SHOP_DOMAIN:}") String shopDomain) {
        this.base = base(storefrontBaseUrl, shopDomain);
    }

    public String cart() {
        return base + "/cart";
    }

    /** The product page, Shopify's default variant. */
    public String product(String handle) {
        return base + "/products/" + URLEncoder.encode(handle, StandardCharsets.UTF_8);
    }

    /** The product page with the variant selected. */
    public String product(String handle, String variantId) {
        return base + "/products/" + URLEncoder.encode(handle, StandardCharsets.UTF_8)
                + "?variant=" + URLEncoder.encode(variantId, StandardCharsets.UTF_8);
    }

    /** {@code products/<handle>[?variant=<id>]}: the WhatsApp button suffix after {@code https://wumika.com/}. */
    static String productPath(String handle, String variantId) {
        var path = "products/" + URLEncoder.encode(handle, StandardCharsets.UTF_8);
        return variantId == null || variantId.isBlank() ? path
                : path + "?variant=" + URLEncoder.encode(variantId, StandardCharsets.UTF_8);
    }

    /** A path on the storefront, e.g. Shopify's checkout recovery path; no leading slash. */
    public String path(String path) {
        return base + "/" + path.replaceAll("^/+", "");
    }

    /**
     * The path and query of Shopify's {@code abandoned_checkout_url}, without
     * the leading slash, so it opens on our domain (the WhatsApp button is
     * {@code https://wumika.com/{{checkout_path}}}). Null or unreadable → the cart.
     */
    static String checkoutPath(String recoveryUrl) {
        if (recoveryUrl == null || recoveryUrl.isBlank()) return "cart";
        try {
            var u = java.net.URI.create(recoveryUrl.strip());
            var path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("^/+", "");
            if (path.isEmpty()) return "cart";
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return "cart";
        }
    }

    static String base(String storefrontBaseUrl, String shopDomain) {
        if (storefrontBaseUrl != null && !storefrontBaseUrl.isBlank()) return storefrontBaseUrl.replaceAll("/+$", "");
        if (shopDomain == null || shopDomain.isBlank()) {
            throw new IllegalStateException("set STOREFRONT_BASE_URL (or SHOPIFY_SHOP_DOMAIN) for storefront links");
        }
        return "https://" + shopDomain;
    }
}
