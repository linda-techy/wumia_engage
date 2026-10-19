package in.brand.engage.worker.intents;

import in.brand.engage.persistence.Sql;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;

/**
 * The product's main photo (V38 {@code products.image_url}, from products/update
 * or the storefront import), in the two forms the channels take:
 *
 * <ul>
 * <li>{@code image}: a push notification's large image. Shopify's CDN resizes
 *     on request, so the URL asks for 1024 px wide: big enough for a phone,
 *     small enough to load before the notification is drawn.</li>
 * <li>{@code photo}: the WhatsApp header photo, as {@code <product id>-<hash>}
 *     for ingest-api's {@code /media/product/} (Meta takes only JPEG or PNG,
 *     which the CDN does not promise). The hash is of the photo's URL, so a
 *     new photo is a new link and Meta's cache of the old one is not used.</li>
 * </ul>
 * Nothing is added when the photo is unknown; a photo template then falls back to its text version.
 */
final class ProductImages {

    static final int WIDTH = 1024;

    /** A product with a photo. */
    record Photo(String productId, String url) {}

    private ProductImages() {}

    static Photo byProductId(Connection c, String productId) throws SQLException {
        if (productId == null || productId.isBlank()) return null;
        return one(c, "SELECT product_id, image_url FROM products WHERE product_id = ? AND image_url IS NOT NULL", productId);
    }

    static Photo byHandle(Connection c, String handle) throws SQLException {
        if (handle == null || handle.isBlank()) return null;
        return one(c, """
                SELECT product_id, image_url FROM products WHERE handle = ? AND image_url IS NOT NULL
                 ORDER BY updated_at DESC LIMIT 1""", handle);
    }

    /** Puts {@code image} and {@code photo} into the vars when there is a photo. */
    static void put(Map<String, String> vars, Photo photo) {
        if (photo == null) return;
        var sized = sized(photo.url());
        if (sized == null) return;
        vars.put("image", sized);
        vars.put("photo", photoRef(photo));
    }

    /** {@code <product id>-<first 8 hex of SHA-256(url)>}, the path ingest-api serves the photo at. */
    static String photoRef(Photo photo) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(photo.url().getBytes(StandardCharsets.UTF_8));
            return photo.productId() + "-" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sized(String url) {
        if (url == null || !url.startsWith("https://")) return null;
        if (!url.contains("cdn.shopify.com") || url.contains("width=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "width=" + WIDTH;
    }

    private static Photo one(Connection c, String sql, String param) throws SQLException {
        try (var ps = Sql.prepare(c, sql, param); var rs = ps.executeQuery()) {
            return rs.next() ? new Photo(rs.getString(1), rs.getString(2)) : null;
        }
    }
}
