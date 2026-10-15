package in.brand.engage.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The one-click unsubscribe link in every marketing email (P7-T01):
 * {@code <PUBLIC_BASE_URL>/u/<token>}. The token names the person and is
 * signed, so nobody can unsubscribe someone else by guessing; it never
 * expires, because an unsubscribe link must keep working (RFC 8058).
 *
 * <p>Keyed from the Shopify app secret, like {@link BeaconSignature}, so there
 * is nothing extra to provision. {@code <base64url(identity uuid)><base64url(16-byte mac)>}.
 */
public final class UnsubscribeToken {

    private static final int MAC_BYTES = 16;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final String key;

    private UnsubscribeToken(String key) {
        this.key = key;
    }

    /** @throws IllegalStateException if the app secret is not configured */
    public static UnsubscribeToken fromAppSecret(String shopifyApiSecret) {
        if (shopifyApiSecret == null || shopifyApiSecret.isBlank() || shopifyApiSecret.contains("CHANGE_ME")) {
            throw new IllegalStateException("SHOPIFY_API_SECRET is not set: unsubscribe links cannot be signed");
        }
        var derived = Hmacs.sha256(shopifyApiSecret, "engage:unsubscribe:v1".getBytes(StandardCharsets.UTF_8));
        return new UnsubscribeToken(Base64.getEncoder().encodeToString(derived));
    }

    public String of(UUID identity) {
        var id = ENC.encodeToString(bytes(identity));          // 22 chars
        return id + ENC.encodeToString(mac(id));                // + 22 chars
    }

    /** The person, if the token is one we made. */
    public Optional<UUID> parse(String token) {
        if (token == null || token.length() != 44) return Optional.empty();
        var id = token.substring(0, 22);
        if (!Hmacs.constantTimeEquals(ENC.encodeToString(mac(id)), token.substring(22))) return Optional.empty();
        try {
            var b = ByteBuffer.wrap(DEC.decode(id));
            return Optional.of(new UUID(b.getLong(), b.getLong()));
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String id) {
        return Arrays.copyOf(Hmacs.sha256(key, ("unsubscribe:" + id).getBytes(StandardCharsets.UTF_8)), MAC_BYTES);
    }

    private static byte[] bytes(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }
}
