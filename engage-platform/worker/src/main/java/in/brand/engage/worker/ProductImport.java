package in.brand.engage.worker;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.intents.Storefront;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code worker products-import}: fills {@code products} from the storefront's
 * public catalogue ({@code <STOREFRONT_BASE_URL>/products.json}), for the
 * products no products/update webhook has brought yet. Photo templates need
 * {@code image_url}; without it they fall back to their text version.
 *
 * <p>Read-only towards Shopify (the same JSON any visitor's browser can load)
 * and holds no customer data. Like the webhook, a row is only overwritten by a
 * newer {@code updated_at}, so running it again is harmless.
 *
 * <p>Shopify's edge (Cloudflare) rate-limits the feed per IP and answered the
 * production server's Java client 429 where curl got 200. So: HTTP/1.1 with a
 * named User-Agent, a pause between pages, and up to five waits on 429. If it
 * still refuses, {@code products-import --from <dir>} reads pages saved with
 * curl ({@code page-1.json}, {@code page-2.json}, …).
 */
final class ProductImport {

    static final int PAGE = 250;
    static final int MAX_PAGES = 100;
    static final String USER_AGENT = "Mozilla/5.0 (compatible; WUMIKA-Engage/1.0; +https://engage.wumika.com)";
    static final int ATTEMPTS = 5;

    private ProductImport() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].equals("products-import");
    }

    static void run(String[] args) {
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            var db = ctx.getBean(Db.class);
            var json = ctx.getBean(JsonMapper.class);
            var storefront = ctx.getBean(Storefront.class);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            java.nio.file.Path from = args.length > 2 && args[1].equals("--from") ? java.nio.file.Path.of(args[2]) : null;
            int seen = 0, changed = 0, photos = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                List<Map<String, Object>> products;
                if (from != null) {
                    var file = from.resolve("page-" + page + ".json");
                    if (!java.nio.file.Files.exists(file)) break;
                    products = parse(json, readAll(file));
                } else {
                    if (page > 1) sleep(1500);                 // gentle on the storefront
                    products = page(http, json, storefront.path("products.json?limit=" + PAGE + "&page=" + page));
                }
                if (products.isEmpty()) break;
                seen += products.size();
                for (var p : products) if (image(p) != null) photos++;
                changed += db.inTx(c -> {
                    int n = 0;
                    for (var p : products) n += upsert(c, p);
                    return n;
                });
                if (products.size() < PAGE) break;
            }
            System.out.printf("products-import: %d products read (%d with a photo), %d rows inserted or updated%n",
                    seen, photos, changed);
        }
    }

    private static List<Map<String, Object>> page(HttpClient http, JsonMapper json, String url) {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, */*").header("User-Agent", USER_AGENT).GET().build();
        try {
            for (int attempt = 1; ; attempt++) {
                var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200) return parse(json, res.body());
                if (res.statusCode() != 429 || attempt == ATTEMPTS) {
                    throw new IllegalStateException("GET " + url + ": HTTP " + res.statusCode()
                            + (res.statusCode() == 429 ? " (rate-limited; save the pages with curl and use --from <dir>)" : ""));
                }
                long wait = res.headers().firstValue("Retry-After").map(ProductImport::seconds).orElse(5L << (attempt - 1));
                System.out.printf("products-import: rate-limited, waiting %d s%n", wait);
                sleep(Math.min(wait, 120) * 1000);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static long seconds(String retryAfter) {
        try {
            return Math.max(1, Long.parseLong(retryAfter.strip()));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAll(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Map<String, Object>> parse(JsonMapper json, byte[] bytes) {
        try {
            Map<String, Object> body = json.readValue(bytes, Argument.mapOf(String.class, Object.class));
            var out = new ArrayList<Map<String, Object>>();
            if (body.get("products") instanceof List<?> list) {
                for (var o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked") var p = (Map<String, Object>) m;
                        out.add(p);
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same columns as ingest-api's shopify_product_upsert.sql, from the storefront's JSON. */
    static int upsert(Connection c, Map<String, Object> p) throws SQLException {
        var id = str(p.get("id"));
        var updated = str(p.get("updated_at"));
        if (id == null || updated == null) return 0;
        var tags = new ArrayList<String>();
        if (p.get("tags") instanceof List<?> ts) {
            for (var t : ts) {
                var s = String.valueOf(t).strip().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) tags.add(s);
            }
        }
        Integer sizePosition = null;
        if (p.get("options") instanceof List<?> os) {
            for (var o : os) {
                if (o instanceof Map<?, ?> om && om.get("name") != null && om.get("position") instanceof Number pos
                        && List.of("size", "sizes").contains(String.valueOf(om.get("name")).strip().toLowerCase(Locale.ROOT))) {
                    sizePosition = pos.intValue();
                    break;
                }
            }
        }
        var type = str(p.get("product_type"));
        return Sql.update(c, """
                INSERT INTO products (product_id, handle, title, product_type, tags, size_position, image_url, updated_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS smallint), ?, ?)
                ON CONFLICT (product_id) DO UPDATE
                   SET handle = EXCLUDED.handle, title = EXCLUDED.title, product_type = EXCLUDED.product_type,
                       tags = EXCLUDED.tags, size_position = EXCLUDED.size_position, image_url = EXCLUDED.image_url,
                       updated_at = EXCLUDED.updated_at
                 WHERE products.updated_at < EXCLUDED.updated_at
                    OR (products.image_url IS NULL AND EXCLUDED.image_url IS NOT NULL)""",
                id, str(p.get("handle")), str(p.get("title")),
                type == null ? null : type.toLowerCase(Locale.ROOT),
                tags.toArray(String[]::new), sizePosition, image(p), OffsetDateTime.parse(updated));
    }

    /** The main photo: the first image, https only (the products.image_url check). */
    static String image(Map<String, Object> p) {
        if (p.get("images") instanceof List<?> images && !images.isEmpty()
                && images.getFirst() instanceof Map<?, ?> first && first.get("src") instanceof String src
                && src.startsWith("https://")) {
            return src;
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        var s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }
}
