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
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** V44: the countdown poster, drawn at fetch time with the time left. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CountdownImageTest {

    @Inject @Client("/") HttpClient client;
    @Inject Db db;

    @Test void the_band_draws_without_fonts_for_every_shape_of_time_left() throws Exception {
        for (var left : new Duration[] {Duration.ofSeconds(2 * 3600 + 14 * 60 + 33), Duration.ofHours(130), Duration.ZERO}) {
            var jpeg = CountdownImageController.jpeg(CountdownImageController.draw(null, left));
            var img = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertEquals(1024, img.getWidth());
            assertEquals(512, img.getHeight());
            // A gold pixel in the band's top rule: the band is there.
            var rgb = new java.awt.Color(img.getRGB(512, 512 - 196 + 1));
            assertTrue(rgb.getRed() > 200 && rgb.getGreen() > 150, "gold rule, got " + rgb);
        }
        // For a look at it: build/countdown-sample.jpg
        Files.write(Path.of("build/countdown-sample.jpg"),
                CountdownImageController.jpeg(CountdownImageController.draw(null, Duration.ofSeconds(2 * 3600 + 14 * 60 + 33))));
    }

    int status(String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), byte[].class).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    UUID campaign(boolean poster) {
        return db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO operators (email, full_name, password_hash, status)
                    VALUES ('countdown-test@wumika.com', 'Test', 'x', 'active') ON CONFLICT (email) DO NOTHING""");
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category) VALUES ('push_campaign_custom_v1', 'push', 'marketing')
                    ON CONFLICT (key) DO NOTHING""");
            try (var ps = Sql.prepare(c, """
                    INSERT INTO campaigns (name, channel, template_key, vars, ends_at, countdown_poster, created_by)
                    VALUES ('Dhanteras', 'push', 'push_campaign_custom_v1', '{"image":"https://engage.wumika.com/media/none.jpg"}',
                            now() + interval '3 hours', ?, (SELECT id FROM operators WHERE email = 'countdown-test@wumika.com'))
                    RETURNING id""", poster); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        });
    }

    @Test void a_countdown_campaign_gets_a_fresh_jpeg_every_fetch() {
        var id = campaign(true);
        var res = client.toBlocking().exchange(HttpRequest.GET("/media/countdown/" + id + "?s=42"), byte[].class);
        assertEquals(200, res.code());
        assertEquals("image/jpeg", res.getContentType().orElseThrow().toString());
        assertEquals("no-store", res.getHeaders().get("Cache-Control"));
        assertTrue(res.body().length > 10_000);
    }

    @Test void nothing_else_is_drawn() {
        assertEquals(404, status("/media/countdown/" + campaign(false)), "countdown poster off");
        assertEquals(404, status("/media/countdown/" + UUID.randomUUID()), "unknown");
        assertEquals(404, status("/media/countdown/not-a-uuid"));
    }
}
