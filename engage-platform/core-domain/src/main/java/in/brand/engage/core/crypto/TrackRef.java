package in.brand.engage.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.OptionalLong;

/**
 * The tracking link's reference to a shipment (P4-T08):
 * {@code https://<store>/apps/push/t/<ref>}, which the storefront proxy
 * redirects to the courier's tracking page. Shipment ids are sequential, so
 * the ref is the id plus a signature: nobody can walk the ids and read other
 * people's parcels. Short, because a DLT SMS variable is at most 30
 * characters: {@code <id in base 36>-<10 base64url chars>}.
 *
 * <p>Keyed from the Shopify app secret, like {@link BeaconSignature}, so
 * there is nothing extra to provision. Rotating it breaks links already sent.
 */
public final class TrackRef {

    private static final int BYTES = 8;           // 64 bits, 11 chars; checked online behind a rate limit

    private final String key;

    private TrackRef(String key) {
        this.key = key;
    }

    /** @throws IllegalStateException if the app secret is not configured */
    public static TrackRef fromAppSecret(String shopifyApiSecret) {
        if (shopifyApiSecret == null || shopifyApiSecret.isBlank() || shopifyApiSecret.contains("CHANGE_ME")) {
            throw new IllegalStateException("SHOPIFY_API_SECRET is not set: tracking links cannot be signed");
        }
        var derived = Hmacs.sha256(shopifyApiSecret, "engage:track-ref:v1".getBytes(StandardCharsets.UTF_8));
        return new TrackRef(Base64.getEncoder().encodeToString(derived));
    }

    public String of(long shipmentId) {
        var id = Long.toString(shipmentId, 36);
        return id + "-" + sign(id);
    }

    /** The shipment id, if the ref is one we made. */
    public OptionalLong parse(String ref) {
        if (ref == null || ref.length() > 30) return OptionalLong.empty();
        int dash = ref.indexOf('-');
        if (dash < 1) return OptionalLong.empty();
        var id = ref.substring(0, dash);
        if (!id.matches("[0-9a-z]{1,13}") || !Hmacs.constantTimeEquals(sign(id), ref.substring(dash + 1))) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(id, 36));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private String sign(String id) {
        var mac = Hmacs.sha256(key, ("shipment:" + id).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(mac, BYTES));
    }
}
