package in.brand.engage.core.shopify;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.shopify.AppProxyVerifier.ProxySignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShopifyVerifiersTest {

    // All expected values computed independently (Python hmac/hashlib/base64).
    static final String SECRET = "shpss_test_secret";
    static final String SHOP = "test-store.myshopify.com";

    /* ------------------------------ webhooks ------------------------------ */

    static final byte[] ORDER = """
            {"id":820982911946154508,"name":"#1042","total_price":"1299.00"}"""
            .getBytes(StandardCharsets.UTF_8);
    static final String ORDER_HMAC = "Z1xt9fSSTy3xX86M29SNgVtn8kCb9J1AXlFEPW6NFGc=";

    @Test void webhook_accepts_valid_hmac() {
        assertTrue(new ShopifyWebhookVerifier(SECRET).verify(ORDER, ORDER_HMAC));
    }

    @Test void webhook_rejects_tampered_total() {
        var tampered = new String(ORDER, StandardCharsets.UTF_8).replace("1299.00", "1.00")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(new ShopifyWebhookVerifier(SECRET).verify(tampered, ORDER_HMAC));
    }

    /* ------------------------------ app proxy ----------------------------- */

    static final Instant SIGNED_AT = Instant.ofEpochSecond(1758271200L);

    static Map<String, List<String>> query(String customerId, String signature) {
        var q = new HashMap<String, List<String>>();
        q.put("shop", List.of(SHOP));
        q.put("path_prefix", List.of("/apps/push"));
        q.put("timestamp", List.of("1758271200"));
        q.put("logged_in_customer_id", List.of(customerId));
        q.put("extra", List.of("1", "2"));           // multi-valued: joined with commas
        q.put("signature", List.of(signature));
        return q;
    }

    final AppProxyVerifier proxy = new AppProxyVerifier(SECRET, SHOP);

    @Test void proxy_returns_signed_customer_id() {
        var ctx = proxy.verify(
                query("7788", "0c7004a5bfc28595117759604f449fe49867ebb1b08c3a50c55e5b525b086623"),
                SIGNED_AT.plusSeconds(30));
        assertEquals("7788", ctx.loggedInCustomerId().orElseThrow());
    }

    @Test void proxy_empty_customer_id_means_anonymous() {
        var ctx = proxy.verify(
                query("", "cc71efaf48f1b8e7cfc58f2617c7a5e1d804ccaa442e2a534e5774f92a964e60"),
                SIGNED_AT);
        assertTrue(ctx.loggedInCustomerId().isEmpty());
    }

    @Test void proxy_rejects_forged_customer_id() {
        // Attacker swaps in another customer's id but cannot re-sign.
        var q = query("9999", "0c7004a5bfc28595117759604f449fe49867ebb1b08c3a50c55e5b525b086623");
        assertThrows(ProxySignatureException.class, () -> proxy.verify(q, SIGNED_AT));
    }

    @Test void proxy_rejects_replay_after_five_minutes() {
        var q = query("7788", "0c7004a5bfc28595117759604f449fe49867ebb1b08c3a50c55e5b525b086623");
        var e = assertThrows(ProxySignatureException.class,
                () -> proxy.verify(q, SIGNED_AT.plusSeconds(301)));
        assertEquals("stale request", e.getMessage());
    }

    @Test void proxy_rejects_other_shop() {
        var other = new AppProxyVerifier(SECRET, "someone-else.myshopify.com");
        var q = query("7788", "0c7004a5bfc28595117759604f449fe49867ebb1b08c3a50c55e5b525b086623");
        assertThrows(ProxySignatureException.class, () -> other.verify(q, SIGNED_AT));
    }

    @Test void proxy_rejects_unsigned() {
        var q = query("7788", "x");
        q.remove("signature");
        assertThrows(ProxySignatureException.class, () -> proxy.verify(q, SIGNED_AT));
    }

    /* ------------------------------ cart tokens --------------------------- */

    @Test void cart_token_suffix_is_stripped() {
        assertEquals("Z2NwLXVz", CartTokens.normalise("Z2NwLXVz?key=abc123"));
        assertEquals("Z2NwLXVz", CartTokens.normalise(" Z2NwLXVz "));
        assertNull(CartTokens.normalise("?key=only"));
        assertNull(CartTokens.normalise(null));
    }
}
