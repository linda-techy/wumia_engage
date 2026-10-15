package in.brand.engage.worker.intents;

import in.brand.engage.persistence.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * The product's main photo (V38 {@code products.image_url}, from products/update)
 * as a push notification's large image. Shopify's CDN resizes on request, so
 * the URL asks for 1024 px wide: big enough for a phone, small enough to load
 * before the notification is drawn. Nothing is added when the photo is unknown.
 */
final class ProductImages {

    static final int WIDTH = 1024;

    private ProductImages() {}

    static String byProductId(Connection c, String productId) throws SQLException {
        if (productId == null || productId.isBlank()) return null;
        return one(c, "SELECT image_url FROM products WHERE product_id = ?", productId);
    }

    static String byHandle(Connection c, String handle) throws SQLException {
        if (handle == null || handle.isBlank()) return null;
        return one(c, "SELECT image_url FROM products WHERE handle = ? ORDER BY updated_at DESC LIMIT 1", handle);
    }

    /** Puts {@code image} into the vars when there is one. */
    static void put(Map<String, String> vars, String url) {
        if (url != null) vars.put("image", url);
    }

    static String sized(String url) {
        if (url == null || !url.startsWith("https://")) return null;
        if (!url.contains("cdn.shopify.com") || url.contains("width=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "width=" + WIDTH;
    }

    private static String one(Connection c, String sql, String param) throws SQLException {
        try (var ps = Sql.prepare(c, sql, param); var rs = ps.executeQuery()) {
            return rs.next() ? sized(rs.getString(1)) : null;
        }
    }
}
