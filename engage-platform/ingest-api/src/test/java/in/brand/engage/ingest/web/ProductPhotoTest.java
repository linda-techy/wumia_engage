package in.brand.engage.ingest.web;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** WhatsApp header photos: only our products' CDN photos, only JPEG or PNG. Nothing leaves the JVM. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ProductPhotoTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    static final List<String> FETCHED = new CopyOnWriteArrayList<>();

    /** Stands in for Shopify's CDN: a PNG for any .png, nothing for anything else. */
    @Singleton
    @Replaces(ProductPhotoController.Fetcher.class)
    static class TestFetcher extends ProductPhotoController.Fetcher {
        @Override protected ProductPhotoController.Photo fetch(String url) {
            FETCHED.add(url);
            return url.contains(".png") ? new ProductPhotoController.Photo("image/png", PNG) : null;
        }
    }

    @Inject @Client("/") HttpClient client;
    @Inject Db db;

    int status(String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), byte[].class).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    String product(String imageUrl) {
        var id = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L));
        db.inTx(c -> Sql.update(c, "INSERT INTO products (product_id, handle, image_url, updated_at) VALUES (?, ?, ?, now())",
                id, "p-" + id, imageUrl));
        return id;
    }

    @Test void a_product_photo_is_fetched_at_1024px_and_served_with_its_type() {
        var id = product("https://cdn.shopify.com/s/files/1/0664/9679/0716/files/a010.png?v=1791914674");
        var res = client.toBlocking().exchange(HttpRequest.GET("/media/product/" + id + "-9f1c2a7e"), byte[].class);
        assertEquals(200, res.code());
        assertArrayEquals(PNG, res.body());
        assertEquals("image/png", res.getContentType().orElseThrow().toString());
        assertEquals("nosniff", res.getHeaders().get("X-Content-Type-Options"));
        assertTrue(FETCHED.contains("https://cdn.shopify.com/s/files/1/0664/9679/0716/files/a010.png?v=1791914674&width=1024"),
                FETCHED.toString());

        assertEquals(200, status("/media/product/" + id), "the hash is optional");
    }

    @Test void nothing_but_a_known_product_on_shopifys_cdn_is_ever_fetched() {
        FETCHED.clear();
        assertEquals(404, status("/media/product/1"), "unknown product");
        assertEquals(404, status("/media/product/" + product("https://evil.example/a.png")), "not the CDN");
        assertEquals(404, status("/media/product/" + product("https://cdn.shopify.com.evil.example/a.png")), "look-alike host");
        assertEquals(404, status("/media/product/abc"), "not a product id");
        assertEquals(404, status("/media/product/..%2F..%2Fetc"), "not a product id");
        assertEquals(List.of(), FETCHED, "none of those reached the network");

        assertEquals(404, status("/media/product/" + product("https://cdn.shopify.com/s/files/1/x/files/a.webp?v=1")),
                "the CDN gave nothing Meta can take");
    }

    @Test void the_cdn_url_is_sized_once() {
        assertEquals("https://cdn.shopify.com/a.png?width=1024", ProductPhotoController.Fetcher.sized("https://cdn.shopify.com/a.png"));
        assertEquals("https://cdn.shopify.com/a.png?v=1&width=640", ProductPhotoController.Fetcher.sized("https://cdn.shopify.com/a.png?v=1&width=640"));
        assertNull(ProductPhotoController.Fetcher.sized("http://cdn.shopify.com/a.png"));
        assertNull(ProductPhotoController.Fetcher.sized("https://cdn.shopify.com:8443/a.png"));
    }
}
