package in.brand.engage.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Signs the send id a push carries, so the service worker's click beacon can
 * be trusted. Send ids are sequential: without this, anyone could post
 * {@code sid=1..n} through the storefront proxy, mark every push clicked, and
 * so end other people's cascades. The worker signs {@code sid} into the push
 * payload; ingest-api checks the signature the beacon echoes back.
 *
 * <p>The key is derived from the Shopify app secret, which both services
 * already hold, so there is no extra secret to provision. Rotating the app
 * secret invalidates beacons from pushes already delivered; that loses a few
 * click records, nothing else.
 */
public final class BeaconSignature {

    private static final int BYTES = 16;          // 128 bits: forging needs the key, not luck

    private final String key;

    private BeaconSignature(String key) {
        this.key = key;
    }

    /** @throws IllegalStateException if the app secret is not configured */
    public static BeaconSignature fromAppSecret(String shopifyApiSecret) {
        if (shopifyApiSecret == null || shopifyApiSecret.isBlank() || shopifyApiSecret.contains("CHANGE_ME")) {
            throw new IllegalStateException("SHOPIFY_API_SECRET is not set: push click beacons cannot be signed");
        }
        var derived = Hmacs.sha256(shopifyApiSecret, "engage:push-beacon:v1".getBytes(StandardCharsets.UTF_8));
        return new BeaconSignature(Base64.getEncoder().encodeToString(derived));
    }

    public String sign(long sendId) {
        var mac = Hmacs.sha256(key, ("sid:" + sendId).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(mac, BYTES));
    }

    public boolean verify(long sendId, String signature) {
        return signature != null && Hmacs.constantTimeEquals(sign(sendId), signature);
    }
}
