package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * P7-T06: every public endpoint against malformed input. Seeded, so a failure
 * reproduces; the seed and the body are in the message. Whatever arrives, the
 * answer is a 4xx (or a 2xx for what is valid), never a 5xx, and never a
 * stack trace or class name in the response.
 *
 * <p>Storefront calls are correctly signed, so the bodies reach validation
 * rather than stopping at the signature check. Shopify webhooks are signed too,
 * and the inbox is drained afterwards: a handler must survive any JSON.
 */
@MicronautTest(transactional = false)
@Property(name = "engage.pixel.write-key", value = EndpointFuzzTest.PIXEL_KEY)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class EndpointFuzzTest {

    static final String PIXEL_KEY = "fuzz-pixel-key-9d2e";
    static final long SEED = 20261015L;
    static final int ROUNDS = 40;

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("DELETE FROM webhook_inbox WHERE delivery_id LIKE 'fuzz-%'");
        }
    }

    /* ------------------------------ samples ------------------------------ */

    static Map<String, Object> obj(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final Map<String, Map<String, Object>> STOREFRONT = Map.of(
            "/register", obj("anonId", UUID.randomUUID().toString(), "token", "tok", "surface", "add_to_cart", "platform", "WEB",
                    "browser", "chrome", "copyVersion", "push_v1", "copyText", "Get offers", "page", "/products/x"),
            "/refresh", obj("anonId", UUID.randomUUID().toString(), "token", "tok2", "previous", "tok"),
            "/unregister", obj("anonId", UUID.randomUUID().toString(), "token", "tok"),
            "/prompt-event", obj("anonId", UUID.randomUUID().toString(), "surface", "cart", "step", "soft_shown",
                    "platform", "WEB", "browser", "chrome", "reason", null),
            "/cart", obj("anonId", UUID.randomUUID().toString(), "cartToken", "Z2NwLXVzLWVhc3Qx"),
            "/notify-me", obj("anonId", UUID.randomUUID().toString(), "variantId", "44581230001",
                    "productHandle", "linen-kurta", "sizeLabel", "M"),
            "/engagement", obj("kind", "click", "sid", "123", "sig", "abc", "sw", "v1"));

    static final Map<String, Object> PIXEL = obj("name", "product_viewed", "clientId", UUID.randomUUID().toString(),
            "at", "2026-10-15T06:30:00Z", "anonId", null, "productId", "8801", "variantId", "44581230001",
            "productTitle", "Linen Kurta", "productHandle", "linen-kurta", "pricePaise", 189900);

    static final Map<String, Object> ORDER = obj("id", 6012345678901L, "name", "#1", "email", "fuzz@example.com",
            "total_price", "1299.00", "created_at", "2026-10-15T10:00:00+05:30",
            "customer", obj("id", 1, "email", "fuzz@example.com", "phone", "+91 98765 43210"),
            "line_items", List.of(obj("id", 1, "variant_id", 2, "quantity", 1, "price", "1299.00")));

    /* ----------------------------- generators ---------------------------- */

    static final Object[] ODD_VALUES = {
            null, 0, -1, Long.MAX_VALUE, 1.5e308, true, "", " ", "null", "\u0000", "😀", "<script>alert(1)</script>",
            "' OR 1=1 --", "../../etc/passwd", "%00", "2026-13-45T99:99:99Z", "x".repeat(70_000), List.of(), List.of(1, "a"),
            Map.of(), Map.of("a", Map.of("b", List.of())),
    };

    /** A valid body with one to three fields broken, dropped or added. */
    static String mutate(Random rnd, Map<String, Object> sample) {
        var m = new LinkedHashMap<>(sample);
        var keys = new ArrayList<>(m.keySet());
        int n = 1 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
            switch (rnd.nextInt(4)) {
                case 0 -> m.put(keys.get(rnd.nextInt(keys.size())), ODD_VALUES[rnd.nextInt(ODD_VALUES.length)]);
                case 1 -> m.remove(keys.get(rnd.nextInt(keys.size())));
                case 2 -> m.put("x" + rnd.nextInt(1000), ODD_VALUES[rnd.nextInt(ODD_VALUES.length)]);
                default -> m.replaceAll((k, v) -> v instanceof String ? ODD_VALUES[rnd.nextInt(ODD_VALUES.length)] : v);
            }
        }
        return json(m);
    }

    /** Not JSON, or not the JSON anyone expects. */
    static String garbage(Random rnd, Map<String, Object> sample) {
        var valid = json(sample);
        return switch (rnd.nextInt(9)) {
            case 0 -> "";
            case 1 -> valid.substring(0, rnd.nextInt(Math.max(1, valid.length())));
            case 2 -> "[" + valid + "]";
            case 3 -> "\"" + valid.replace("\"", "'") + "\"";
            case 4 -> "[".repeat(5_000) + "]".repeat(5_000);
            case 5 -> "{\"a\":".repeat(2_000) + "1" + "}".repeat(2_000);
            case 6 -> {
                var b = new byte[1 + rnd.nextInt(400)];
                rnd.nextBytes(b);
                yield new String(b, StandardCharsets.ISO_8859_1);
            }
            case 7 -> valid.replace(":", ":::").replace(",", "");
            default -> "null";
        };
    }

    static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) {
            var b = new StringBuilder("\"");
            for (char ch : s.toCharArray()) {
                switch (ch) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    default -> {
                        if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                        else b.append(ch);
                    }
                }
            }
            return b.append('"').toString();
        }
        if (v instanceof Map<?, ?> m) {
            var parts = new ArrayList<String>();
            m.forEach((k, val) -> parts.add(json(String.valueOf(k)) + ":" + json(val)));
            return "{" + String.join(",", parts) + "}";
        }
        if (v instanceof List<?> l) return "[" + String.join(",", l.stream().map(EndpointFuzzTest::json).toList()) + "]";
        return String.valueOf(v);
    }

    static final String[] CONTENT_TYPES = {"application/json", "application/json", "text/plain", "application/x-www-form-urlencoded", null};

    /* ------------------------------- checks ------------------------------ */

    record Answer(int code, String body) {}

    Answer call(MutableHttpRequest<?> req) {
        try {
            var res = client.toBlocking().exchange(req, String.class);
            return new Answer(res.code(), res.getBody().orElse(""));
        } catch (HttpClientResponseException e) {
            return new Answer(e.code(), e.getResponse().getBody(String.class).orElse(""));
        }
    }

    static void assertSafe(String what, String body, Answer a) {
        var shown = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        assertTrue(a.code() < 500, () -> what + " answered " + a.code() + " (seed " + SEED + ") for: " + shown + "\n" + a.body());
        for (var leak : List.of("Exception", "\tat ", "at in.brand", "java.", "io.micronaut", "org.postgresql", "SQLState")) {
            assertFalse(a.body().contains(leak), () -> what + " leaked '" + leak + "' (seed " + SEED + "): " + a.body());
        }
    }

    MutableHttpRequest<?> post(String uri, String body, Random rnd) {
        MutableHttpRequest<String> req = HttpRequest.POST(uri, body);
        var type = CONTENT_TYPES[rnd.nextInt(CONTENT_TYPES.length)];
        if (type != null) req.contentType(type);
        return req;
    }

    String signedProxy(String path) {
        var q = new TreeMap<String, String>();
        q.put("shop", System.getenv("SHOPIFY_SHOP_DOMAIN"));
        q.put("path_prefix", "/apps/push");
        q.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        q.put("logged_in_customer_id", "");
        var message = new StringBuilder();
        q.forEach((k, v) -> message.append(k).append('=').append(v));
        var signature = Hmacs.sha256Hex(System.getenv("SHOPIFY_API_SECRET"), message.toString().getBytes(StandardCharsets.UTF_8));
        var uri = new StringBuilder("/shopify/proxy").append(path).append('?');
        q.forEach((k, v) -> uri.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return uri.append("signature=").append(signature).toString();
    }

    /* -------------------------------- tests ------------------------------ */

    @Test
    void storefront_endpoints_never_fail_on_bad_bodies() {
        var rnd = new Random(SEED);
        var codes = new java.util.TreeMap<Integer, Integer>();
        for (var e : new TreeMap<>(STOREFRONT).entrySet()) {
            for (int i = 0; i < ROUNDS; i++) {
                var body = rnd.nextBoolean() ? mutate(rnd, e.getValue()) : garbage(rnd, e.getValue());
                var a = call(post(signedProxy(e.getKey()), body, rnd));
                assertSafe("POST /shopify/proxy" + e.getKey(), body, a);
                codes.merge(a.code(), 1, Integer::sum);
            }
        }
        // The bodies reached validation: signed requests were both refused (400) and accepted (2xx).
        assertTrue(codes.containsKey(400), codes::toString);
        assertTrue(codes.keySet().stream().anyMatch(c -> c >= 200 && c < 300), codes::toString);
        assertFalse(codes.containsKey(401), () -> "signature rejected, so nothing was tested: " + codes);
    }

    @Test
    void pixel_events_never_fail_on_bad_bodies() {
        var rnd = new Random(SEED + 1);
        for (int i = 0; i < ROUNDS * 3; i++) {
            var body = rnd.nextBoolean() ? mutate(rnd, PIXEL) : garbage(rnd, PIXEL);
            var req = post("/pixel/events", body, rnd);
            if (rnd.nextInt(5) > 0) req.header("X-Engage-Key", PIXEL_KEY);
            assertSafe("POST /pixel/events", body, call(req));
        }
    }

    @Test
    void the_thank_you_opt_in_never_fails_without_a_valid_session_token() {
        var rnd = new Random(SEED + 2);
        var sample = obj("orderId", "6012345678901", "copyVersion", "wa_v1");
        for (int i = 0; i < ROUNDS; i++) {
            var body = rnd.nextBoolean() ? mutate(rnd, sample) : garbage(rnd, sample);
            var req = post("/shopify/thankyou/whatsapp-optin", body, rnd);
            if (rnd.nextBoolean()) req.header("Authorization", "Bearer " + (rnd.nextBoolean() ? "a.b.c" : garbage(rnd, sample).replaceAll("[^A-Za-z0-9.]", "")));
            assertSafe("POST /shopify/thankyou/whatsapp-optin", body, call(req));
        }
    }

    @Test
    void links_with_forged_or_broken_references_never_fail() {
        var rnd = new Random(SEED + 3);
        String[] refs = {"x", "a.b", "a.b.c", "....", "%2e%2e%2f", "A".repeat(3000), "eyJhbGciOiJub25lIn0.e30.", "0", "-1", "%00", "null"};
        for (int i = 0; i < ROUNDS; i++) {
            var ref = rnd.nextInt(3) == 0 ? UUID.randomUUID().toString() : refs[rnd.nextInt(refs.length)];
            var enc = ref.startsWith("%") ? ref : URLEncoder.encode(ref, StandardCharsets.UTF_8);
            assertSafe("GET /u/" + ref, "", call(HttpRequest.GET("/u/" + enc)));
            assertSafe("POST /u/" + ref, "List-Unsubscribe=One-Click", call(post("/u/" + enc, "List-Unsubscribe=One-Click", rnd)));
            assertSafe("GET /shopify/proxy/t/" + ref, "", call(HttpRequest.GET(signedProxy("/t/" + enc))));
            assertSafe("GET /shopify/proxy/pay/" + ref, "", call(HttpRequest.GET(signedProxy("/pay/" + enc))));
        }
        assertSafe("GET /meta/webhook", "", call(HttpRequest.GET("/meta/webhook?hub.mode=subscribe&hub.challenge=%3Cx%3E&hub.verify_token=")));
    }

    @Test
    void unsigned_provider_webhooks_are_refused_whatever_the_body() {
        var rnd = new Random(SEED + 4);
        for (var path : List.of("/webhooks/shopify", "/webhooks/razorpay", "/meta/webhook", "/webhooks/sms", "/webhooks/courier", "/webhooks/ses")) {
            for (int i = 0; i < ROUNDS / 4; i++) {
                var body = rnd.nextBoolean() ? mutate(rnd, ORDER) : garbage(rnd, ORDER);
                var req = post(path, body, rnd);
                if (rnd.nextBoolean()) req.header("X-Shopify-Hmac-Sha256", "bm9wZQ==").header("X-Razorpay-Signature", "00")
                        .header("X-Hub-Signature-256", "sha256=00");
                var a = call(req);
                assertSafe("POST " + path, body, a);
                assertTrue(a.code() >= 400 || path.equals("/webhooks/sms") || path.equals("/webhooks/courier"),
                        () -> path + " accepted an unsigned body: " + a.code());
            }
        }
    }

    @Test
    void signed_shopify_webhooks_with_odd_json_are_stored_or_refused_and_the_handlers_survive() {
        var rnd = new Random(SEED + 5);
        var topics = List.of("orders/create", "orders/cancelled", "checkouts/update", "carts/update", "customers/update",
                "refunds/create", "fulfillments/update", "customers/redact", "customers/data_request");
        for (int i = 0; i < ROUNDS; i++) {
            var topic = topics.get(rnd.nextInt(topics.size()));
            var body = rnd.nextInt(3) > 0 ? mutate(rnd, ORDER) : garbage(rnd, ORDER);
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            var req = HttpRequest.create(HttpMethod.POST, "/webhooks/shopify").body(bytes)
                    .contentType("application/json")
                    .header("X-Shopify-Topic", topic)
                    .header("X-Shopify-Webhook-Id", "fuzz-" + UUID.randomUUID())
                    .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                    .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), bytes));
            assertSafe("POST /webhooks/shopify " + topic, body, call(req));
        }
        assertDoesNotThrow(() -> processor.drain(), "a malformed payload must fail its own row, not the inbox");
    }
}
