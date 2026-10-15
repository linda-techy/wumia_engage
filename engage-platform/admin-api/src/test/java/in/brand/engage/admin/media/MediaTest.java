package in.brand.engage.admin.media;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Posters for push campaigns: what an upload accepts, and who may upload. */
@MicronautTest(transactional = false)
@Property(name = "PUBLIC_BASE_URL", value = "https://engage-test.example.com/")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class MediaTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        db.inTx(c -> Sql.update(c, "TRUNCATE media"));
    }

    static byte[] image(String format, int w, int h) throws Exception {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xC0392B);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    Map<String, Object> upload(byte[] bytes, String type, String token) {
        return client.toBlocking().retrieve(HttpRequest.POST("/api/media", bytes).contentType(type)
                .header("X-File-Name", "Onam poster <final>.png").bearerAuth(token), Map.class);
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }

    @Test void a_poster_upload_returns_its_public_url_and_is_stored_once() throws Exception {
        var token = data.loginAsCampaignEditor(client);
        var png = image("png", 1024, 512);
        var m = upload(png, "image/png", token);
        var id = (String) m.get("id");
        assertTrue(id.matches("[0-9a-f]{64}"), id);
        assertEquals("https://engage-test.example.com/media/" + id + ".png", m.get("url"));
        assertEquals(1024, ((Number) m.get("width")).intValue());
        assertEquals(true, m.get("wide"), "2:1 is the shape Chrome shows");
        assertEquals("Onam poster final.png", m.get("name"), "the file name is cleaned");

        upload(png, "image/png", token);
        assertEquals("1", db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM media"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }));

        var jpg = upload(image("jpg", 800, 800), "image/jpeg", token);
        assertTrue(jpg.get("url").toString().endsWith(".jpg"));
        assertEquals(false, jpg.get("wide"), "square is allowed, but flagged");
    }

    @Test void only_real_jpeg_or_png_of_a_sensible_size() throws Exception {
        var token = data.loginAsCampaignEditor(client);
        var tooBig = image("png", 3000, 1500);
        var tooSmall = image("png", 200, 100);
        var fine = image("png", 1024, 512);
        assertEquals(400, status(() -> upload("<svg onload=alert(1)>".getBytes(), "image/png", token)), "not really a PNG");
        assertEquals(400, status(() -> upload(tooBig, "image/png", token)), "too big a side");
        assertEquals(400, status(() -> upload(tooSmall, "image/png", token)), "too small");
        var huge = new byte[1_048_577];
        huge[0] = (byte) 0x89;
        assertEquals(400, status(() -> upload(huge, "image/png", token)), "over 1 MB");
        assertEquals(415, status(() -> upload(fine, "image/gif", token)), "type not accepted");
    }

    @Test void viewers_cannot_upload() throws Exception {
        var png = image("png", 1024, 512);
        assertEquals(403, status(() -> upload(png, "image/png", data.loginAsViewer(client))));
    }
}
