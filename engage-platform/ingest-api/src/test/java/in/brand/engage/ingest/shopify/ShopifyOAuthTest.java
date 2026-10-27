package in.brand.engage.ingest.shopify;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.ShopifyTokens;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** V51: the owner approves Engage once; the callback is checked and the offline token is stored encrypted. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ShopifyOAuthTest {

    static final String SHOP = "e4bac4-ef.myshopify.com";
    static final String SECRET = "test-shopify-secret";

    @Inject Db db;

    final List<String> exchanged = new ArrayList<>();
    String grantedScopes = "read_orders,read_fulfillments,read_inventory";
    ShopifyOAuthController controller;

    @BeforeEach void reset() {
        exchanged.clear();
        db.inTx(c -> Sql.update(c, "TRUNCATE shopify_tokens, shopify_oauth_states"));
        ShopifyCodeExchange fake = (shop, code) -> {
            exchanged.add(shop + ":" + code);
            return new ShopifyCodeExchange.Grant("shpat_test_offline_token", grantedScopes);
        };
        controller = new ShopifyOAuthController(db, fake, SHOP, "client-123", SECRET, "https://engage.wumika.com/", Clock.systemUTC());
    }

    /** Follows /start and returns the state Shopify would hand back. */
    String start() {
        var res = controller.start();
        assertTrue(res.code() == 302 || res.code() == 303 || res.code() == 301, "a redirect: " + res.code());
        var location = res.getHeaders().get("Location");
        assertTrue(location.startsWith("https://" + SHOP + "/admin/oauth/authorize?client_id=client-123&scope=read_orders%2C"), location);
        assertTrue(location.contains("&redirect_uri=https%3A%2F%2Fengage.wumika.com%2Fshopify%2Foauth%2Fcallback&state="), location);
        return URLDecoder.decode(location.substring(location.indexOf("&state=") + 7), StandardCharsets.UTF_8);
    }

    /** A callback signed the way Shopify signs it. */
    HttpRequest<?> callback(String shop, String state, String secret) {
        var params = new TreeMap<String, String>();
        params.put("code", "one-time-code");
        params.put("shop", shop);
        params.put("state", state);
        params.put("timestamp", "1793128697");
        var message = String.join("&", params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        var query = new StringBuilder();
        params.forEach((k, v) -> query.append(k).append('=').append(v).append('&'));
        query.append("hmac=").append(Hmacs.sha256Hex(secret, message.getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.GET("/shopify/oauth/callback?" + query);
    }

    @Test void an_approved_callback_stores_the_token_encrypted_and_clients_read_it() {
        var res = controller.callback(callback(SHOP, start(), SECRET));
        assertEquals(200, res.code());
        assertEquals(List.of(SHOP + ":one-time-code"), exchanged);
        assertEquals("shpat_test_offline_token", db.inTx(c -> ShopifyTokens.load(c, SHOP, SECRET)).orElseThrow());
        String raw = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT encode(token_enc, 'escape') FROM shopify_tokens"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertFalse(raw.contains("shpat_test_offline_token"), "never stored in clear");
        assertTrue(db.inTx(c -> ShopifyTokens.load(c, SHOP, "another-secret")).isEmpty(), "a rotated app secret calls for a new approval");
        assertEquals("shpat_test_offline_token", new ShopifyTokens.Cached(db, SHOP, SECRET).get(java.time.Instant.now()));
    }

    @Test void a_forged_replayed_or_foreign_callback_is_refused() {
        var state = start();
        assertEquals(400, controller.callback(callback(SHOP, state, "wrong-secret")).code(), "bad HMAC");
        assertEquals(400, controller.callback(callback("evil-store.myshopify.com", state, SECRET)).code(), "another shop");
        assertEquals(400, controller.callback(callback(SHOP, "never-issued", SECRET)).code(), "a state we never issued");
        assertEquals(200, controller.callback(callback(SHOP, state, SECRET)).code());
        assertEquals(400, controller.callback(callback(SHOP, state, SECRET)).code(), "a state works once");
        assertEquals(1, exchanged.size());
    }

    @Test void a_grant_without_read_orders_is_not_kept() {
        grantedScopes = "read_products";
        assertEquals(400, controller.callback(callback(SHOP, start(), SECRET)).code());
        assertTrue(db.inTx(c -> ShopifyTokens.load(c, SHOP, SECRET)).isEmpty());
    }
}
