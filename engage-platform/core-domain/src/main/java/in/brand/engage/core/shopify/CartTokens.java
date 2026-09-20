package in.brand.engage.core.shopify;

/**
 * The storefront's {@code /cart.js} token can carry a {@code ?key=...} suffix
 * that the {@code carts/update} webhook does not. Both sides must normalise the
 * same way or a push token never joins to its cart. The {@code carts} table
 * enforces this with a CHECK constraint.
 */
public final class CartTokens {

    private CartTokens() {}

    public static String normalise(String token) {
        if (token == null) return null;
        var t = token.trim();
        int q = t.indexOf('?');
        t = q >= 0 ? t.substring(0, q) : t;
        return t.isEmpty() ? null : t;
    }
}
