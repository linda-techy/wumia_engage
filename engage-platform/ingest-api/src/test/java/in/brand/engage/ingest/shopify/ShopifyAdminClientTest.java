package in.brand.engage.ingest.shopify;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Admin client against a local stand-in for Shopify: token exchange, caching, renewal, errors. */
class ShopifyAdminClientTest {

    static final String VARIANT_BODY = """
            {"data":{"inventoryItem":{"variant":{"id":"gid://shopify/ProductVariant/4471","title":"M","product":{"id":"gid://shopify/Product/88","title":"Linen Kurta","handle":"linen-kurta"}}}}}""";

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> tokensSeen = new CopyOnWriteArrayList<>();
    final AtomicReference<String> graphqlReply = new AtomicReference<>(VARIANT_BODY);
    int tokensIssued;
    Instant now = Instant.parse("2026-09-29T10:00:00Z");

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/oauth/access_token", ex -> {
            var form = body(ex);
            requests.add("token " + form);
            if (!form.contains("grant_type=client_credentials") || !form.contains("client_secret=secret-1")) {
                reply(ex, 400, "{\"error\":\"invalid_client\"}");
                return;
            }
            tokensIssued++;
            reply(ex, 200, "{\"access_token\":\"shpat_" + tokensIssued + "\",\"scope\":\"read_inventory,read_products\",\"expires_in\":86399}");
        });
        server.createContext("/admin/api/2026-07/graphql.json", ex -> {
            requests.add("graphql " + body(ex));
            tokensSeen.add(ex.getRequestHeaders().getFirst("X-Shopify-Access-Token"));
            reply(ex, 200, graphqlReply.get());
        });
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    ShopifyAdminClient client(String fixedToken) {
        var clock = new Clock() {
            @Override public Instant instant() { return now; }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
        };
        return new ShopifyAdminClient(JsonMapper.createDefault(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "client-1", "secret-1", fixedToken, "2026-07", clock);
    }

    @Test void exchanges_the_app_credentials_for_a_token_and_resolves_the_variant() {
        var ref = client("").variantOfInventoryItem("9001");

        assertEquals(new ShopifyAdmin.VariantRef("4471", "88", "Linen Kurta", "linen-kurta", "M"), ref.orElseThrow());
        assertEquals(List.of("shpat_1"), tokensSeen);
        assertTrue(requests.get(1).contains("gid://shopify/InventoryItem/9001"), requests.get(1));
    }

    @Test void the_token_is_reused_until_shortly_before_it_expires() {
        var client = client("");
        client.variantOfInventoryItem("1");
        now = now.plus(Duration.ofHours(23));
        client.variantOfInventoryItem("2");
        assertEquals(1, tokensIssued, "one token for a day of lookups");

        now = now.plus(Duration.ofMinutes(56));                // 23h56m: inside the renewal margin
        client.variantOfInventoryItem("3");
        assertEquals(2, tokensIssued);
        assertEquals(List.of("shpat_1", "shpat_1", "shpat_2"), tokensSeen);
    }

    @Test void a_configured_admin_token_is_used_as_is() {
        client("shpat_fixed").variantOfInventoryItem("1");

        assertEquals(0, tokensIssued);
        assertEquals(List.of("shpat_fixed"), tokensSeen);
    }

    @Test void a_deleted_item_is_empty_and_api_errors_throw_for_a_retry() {
        graphqlReply.set("{\"data\":{\"inventoryItem\":null}}");
        assertTrue(client("").variantOfInventoryItem("1").isEmpty());

        graphqlReply.set("{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
        var e = assertThrows(IllegalStateException.class, () -> client("").variantOfInventoryItem("1"));
        assertTrue(e.getMessage().contains("THROTTLED"), e.getMessage());
    }

    @Test void a_refused_token_request_names_the_reason() {
        var bad = new ShopifyAdminClient(JsonMapper.createDefault(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "client-1", "wrong", "", "2026-07", Clock.systemUTC());

        var e = assertThrows(IllegalStateException.class, () -> bad.variantOfInventoryItem("1"));
        assertTrue(e.getMessage().startsWith("Shopify token request 400"), e.getMessage());
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
