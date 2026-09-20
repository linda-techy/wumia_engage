package in.brand.engage.core.shopify;

import in.brand.engage.core.crypto.Hmacs;

/**
 * Verifies {@code X-Shopify-Hmac-Sha256}: base64 HMAC-SHA256 of the RAW request
 * body, keyed with the app's client secret.
 *
 * <p>The body must be the exact bytes received. Deserialising to an object and
 * re-serialising changes whitespace and key order, and every check then fails.
 */
public final class ShopifyWebhookVerifier {

    private final String apiSecret;

    public ShopifyWebhookVerifier(String apiSecret) {
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalArgumentException("Shopify API secret not set");
        this.apiSecret = apiSecret;
    }

    public boolean verify(byte[] rawBody, String headerHmacBase64) {
        if (rawBody == null || headerHmacBase64 == null || headerHmacBase64.isBlank()) return false;
        return Hmacs.constantTimeEquals(Hmacs.sha256Base64(apiSecret, rawBody), headerHmacBase64.trim());
    }
}
