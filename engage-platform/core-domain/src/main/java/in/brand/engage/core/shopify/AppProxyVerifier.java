package in.brand.engage.core.shopify;

import in.brand.engage.core.crypto.Hmacs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Verifies Shopify App Proxy requests ({@code https://store/apps/push/*}).
 *
 * <p>Signature: hex HMAC-SHA256 over every query parameter except
 * {@code signature}, sorted by key, each written {@code key=value} (multiple
 * values joined by commas), concatenated with NO separator.
 *
 * <p>What a valid signature proves: the request came through Shopify's proxy
 * for this shop. What it does NOT prove: that the body is honest. Only the
 * signed query parameters are trusted — above all {@code logged_in_customer_id}.
 */
public final class AppProxyVerifier {

    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final String apiSecret;
    private final String expectedShop;

    public AppProxyVerifier(String apiSecret, String expectedShop) {
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalArgumentException("Shopify API secret not set");
        this.apiSecret = apiSecret;
        this.expectedShop = expectedShop;
    }

    public ProxyContext verify(Map<String, List<String>> query, Instant now) {
        var signature = first(query, "signature")
                .orElseThrow(() -> new ProxySignatureException("unsigned request"));

        var sorted = new TreeMap<String, List<String>>(query);
        sorted.remove("signature");
        var message = new StringBuilder();
        sorted.forEach((k, v) -> message.append(k).append('=').append(String.join(",", v)));

        var expected = Hmacs.sha256Hex(apiSecret, message.toString().getBytes(StandardCharsets.UTF_8));
        if (!Hmacs.constantTimeEquals(expected, signature)) {
            throw new ProxySignatureException("bad signature");
        }

        var shop = first(query, "shop").orElseThrow(() -> new ProxySignatureException("missing shop"));
        if (expectedShop != null && !expectedShop.equalsIgnoreCase(shop)) {
            throw new ProxySignatureException("wrong shop: " + shop);
        }

        // The signature never expires by itself. Without a freshness check a
        // captured URL could be replayed forever.
        long ts;
        try {
            ts = Long.parseLong(first(query, "timestamp").orElse(""));
        } catch (NumberFormatException e) {
            throw new ProxySignatureException("bad timestamp");
        }
        if (Duration.between(Instant.ofEpochSecond(ts), now).abs().compareTo(MAX_SKEW) > 0) {
            throw new ProxySignatureException("stale request");
        }

        // Present but empty when nobody is logged in.
        var customerId = first(query, "logged_in_customer_id").filter(s -> !s.isBlank());
        return new ProxyContext(shop, customerId);
    }

    private static Optional<String> first(Map<String, List<String>> q, String key) {
        var v = q.get(key);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.ofNullable(v.getFirst());
    }

    public record ProxyContext(String shop, Optional<String> loggedInCustomerId) {}

    public static final class ProxySignatureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProxySignatureException(String message) {
            super(message);
        }
    }
}
