package in.brand.engage.persistence;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.core.crypto.SecretBox;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The store's offline Admin API token (V51), granted by the owner at
 * /shopify/oauth/start, encrypted at rest. The key is derived from the app's
 * API secret, which every service already holds: no new secret to manage, and
 * rotating the app secret simply calls for approving Engage again.
 */
public final class ShopifyTokens {

    static final Duration STATE_TTL = Duration.ofMinutes(10);

    private ShopifyTokens() {}

    static byte[] key(String apiSecret) {
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalStateException("SHOPIFY_API_SECRET is not set");
        return Hmacs.sha256(apiSecret, "engage:shopify-offline-token:v1".getBytes(StandardCharsets.UTF_8));
    }

    public static void save(Connection c, String shop, String token, String scopes, String apiSecret) throws SQLException {
        Sql.update(c, """
                INSERT INTO shopify_tokens (shop, token_enc, scopes) VALUES (?, ?, ?)
                ON CONFLICT (shop) DO UPDATE SET token_enc = EXCLUDED.token_enc, scopes = EXCLUDED.scopes, granted_at = now()""",
                shop, SecretBox.encrypt(key(apiSecret), token.getBytes(StandardCharsets.UTF_8)), scopes);
    }

    /** The token, or empty when none was granted or it cannot be decrypted (the app secret changed). */
    public static Optional<String> load(Connection c, String shop, String apiSecret) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT token_enc FROM shopify_tokens WHERE shop = ?", shop); var rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            try {
                return Optional.of(new String(SecretBox.decrypt(key(apiSecret), rs.getBytes(1)), StandardCharsets.UTF_8));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        }
    }

    public static int delete(Connection c, String shop) throws SQLException {
        return Sql.update(c, "DELETE FROM shopify_tokens WHERE shop = ?", shop);
    }

    /** A fresh one-time state for the authorize redirect. */
    public static String newState(Connection c) throws SQLException {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        var state = HexFormat.of().formatHex(bytes);
        Sql.update(c, "DELETE FROM shopify_oauth_states WHERE created_at < now() - interval '1 day'");
        Sql.update(c, "INSERT INTO shopify_oauth_states (state) VALUES (?)", state);
        return state;
    }

    /** True once for a state issued in the last 10 minutes; it cannot be used again. */
    public static boolean consumeState(Connection c, String state, Instant now) throws SQLException {
        if (state == null || state.isBlank()) return false;
        return Sql.update(c, "DELETE FROM shopify_oauth_states WHERE state = ? AND created_at > ?",
                state, now.minus(STATE_TTL).atOffset(java.time.ZoneOffset.UTC)) == 1;
    }

    /** The stored token for API clients, read again every few minutes so a new approval is picked up. */
    public static final class Cached {
        private static final Duration TTL = Duration.ofMinutes(5);

        private final Db db;
        private final String shop;
        private final String apiSecret;
        private volatile String value;
        private volatile Instant readAt;

        public Cached(Db db, String shop, String apiSecret) {
            this.db = db;
            this.shop = shop;
            this.apiSecret = apiSecret;
        }

        /** @return the token, or null when the owner has not approved Engage */
        public String get(Instant now) {
            var at = readAt;
            if (at != null && now.isBefore(at.plus(TTL))) return value;
            if (db == null || shop == null || shop.isBlank() || apiSecret == null || apiSecret.isBlank()) return null;
            value = db.inTx(c -> load(c, shop, apiSecret).orElse(null));
            readAt = now;
            return value;
        }

        public void invalidate() {
            readAt = null;
        }
    }
}
