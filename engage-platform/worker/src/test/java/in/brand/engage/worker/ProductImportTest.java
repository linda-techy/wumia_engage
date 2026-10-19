package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The storefront catalogue (products.json) into products, as products/update would write it. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class ProductImportTest {

    @Inject Db db;
    @Inject JsonMapper json;

    Map<String, Object> product(String id, String updatedAt, String image) throws Exception {
        var images = image == null ? "[]" : "[{\"src\":\"" + image + "\",\"width\":777,\"height\":782}]";
        var text = """
                {"id": %s, "title": "Gold Bracelet", "handle": "gold-bracelet-%s", "product_type": "Bracelet",
                 "tags": [" Gold ", "Gift"], "updated_at": "%s",
                 "options": [{"name": "Title", "position": 1}, {"name": "Size", "position": 2}],
                 "images": %s}""".formatted(id, id, updatedAt, images);
        return json.readValue(text.getBytes(StandardCharsets.UTF_8), Argument.mapOf(String.class, Object.class));
    }

    String row(String id) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT handle || '|' || product_type || '|' || array_to_string(tags, ',') || '|' || size_position
                           || '|' || COALESCE(image_url, '-') FROM products WHERE product_id = ?""", id);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test void a_catalogue_product_lands_like_the_webhook_writes_it_and_never_overwrites_a_newer_one() throws Exception {
        var id = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L));
        var photo = "https://cdn.shopify.com/s/files/1/0664/9679/0716/files/a010.png?v=1791914674";

        var p = product(id, "2026-10-19T18:01:43+05:30", photo);
        assertEquals(1, (int) db.inTx(c -> ProductImport.upsert(c, p)));
        assertEquals("gold-bracelet-" + id + "|bracelet|gold,gift|2|" + photo, row(id));

        var older = product(id, "2026-10-01T10:00:00+05:30", "https://cdn.shopify.com/s/files/old.png");
        assertEquals(0, (int) db.inTx(c -> ProductImport.upsert(c, older)), "an older copy changes nothing");
        assertEquals("gold-bracelet-" + id + "|bracelet|gold,gift|2|" + photo, row(id));
    }

    @Test void only_an_https_first_image_is_the_photo() throws Exception {
        assertNull(ProductImport.image(product("1", "2026-10-19T18:01:43+05:30", null)));
        assertNull(ProductImport.image(product("1", "2026-10-19T18:01:43+05:30", "http://cdn.shopify.com/a.png")));
        assertEquals("https://cdn.shopify.com/a.png", ProductImport.image(product("1", "2026-10-19T18:01:43+05:30", "https://cdn.shopify.com/a.png")));
    }
}
