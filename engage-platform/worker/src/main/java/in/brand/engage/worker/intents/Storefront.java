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

    /** The product page with the variant selected. */
    public String product(String handle, String variantId) {
        return base + "/products/" + URLEncoder.encode(handle, StandardCharsets.UTF_8)
                + "?variant=" + URLEncoder.encode(variantId, StandardCharsets.UTF_8);
    }

    static String base(String storefrontBaseUrl, String shopDomain) {
        if (storefrontBaseUrl != null && !storefrontBaseUrl.isBlank()) return storefrontBaseUrl.replaceAll("/+$", "");
        if (shopDomain == null || shopDomain.isBlank()) {
            throw new IllegalStateException("set STOREFRONT_BASE_URL (or SHOPIFY_SHOP_DOMAIN) for storefront links");
        }
        return "https://" + shopDomain;
    }
}
