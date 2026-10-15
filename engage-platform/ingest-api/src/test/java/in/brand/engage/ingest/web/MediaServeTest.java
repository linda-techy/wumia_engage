package in.brand.engage.ingest.web;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Campaign posters served publicly for the browser to draw under a push. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class MediaServeTest {

    static final String ID = "ab".repeat(32);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Inject @Client("/") HttpClient client;
    @Inject Db db;

    int status(String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), byte[].class).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    @Test void a_poster_is_served_with_a_year_of_cache_and_nosniff() {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO media (id, content_type, bytes, width, height, size_bytes) VALUES (?, 'image/png', ?, 1024, 512, ?)
                ON CONFLICT (id) DO NOTHING""", ID, PNG, PNG.length));
        var res = client.toBlocking().exchange(HttpRequest.GET("/media/" + ID + ".png"), byte[].class);
        assertEquals(200, res.code());
        assertArrayEquals(PNG, res.body());
        assertEquals("image/png", res.getContentType().orElseThrow().toString());
        assertTrue(res.getHeaders().get("Cache-Control").contains("immutable"));
        assertEquals("nosniff", res.getHeaders().get("X-Content-Type-Options"));

        assertEquals(404, status("/media/" + ID + ".jpg"), "the extension must match the stored type");
        assertEquals(404, status("/media/" + "cd".repeat(32) + ".png"), "unknown");
        assertEquals(404, status("/media/..%2F..%2Fetc%2Fpasswd"), "not a poster name");
        assertEquals(404, status("/media/" + ID + ".svg"), "no other types");
    }
}
