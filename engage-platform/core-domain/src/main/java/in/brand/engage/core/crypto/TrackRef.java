package in.brand.engage.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.OptionalLong;

/**
 * Short signed references in customer links, opened through the storefront
 * proxy:
 * <ul>
 * <li>{@link #of} / {@link #parse}: a shipment, for the Track link
 *     ({@code https://<store>/apps/push/t/<ref>}, P4-T08);</li>
 * <li>{@link #ofPayment} / {@link #parsePayment}: a payment-failed cascade
 *     run, for the SMS pay link ({@code https://<store>/apps/push/pay/<ref>}, P5-T02).</li>
 * </ul>
 * Ids are sequential, so each ref is the id plus a signature over its kind
 * and id: nobody can walk the ids, and a tracking ref is not a pay ref. Short,
 * because a DLT SMS variable is at most 30 characters:
 * {@code <id in base 36>-<11 base64url chars>}.
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
        return ref("shipment", shipmentId);
    }

    /** The shipment id, if the ref is one we made. */
    public OptionalLong parse(String ref) {
        return read("shipment", ref);
    }

    public String ofPayment(long cascadeRunId) {
        return ref("pay", cascadeRunId);
    }

    /** The payment-failed run id, if the ref is one we made. */
    public OptionalLong parsePayment(String ref) {
        return read("pay", ref);
    }

    private String ref(String kind, long id) {
        var b36 = Long.toString(id, 36);
        return b36 + "-" + sign(kind, b36);
    }

    private OptionalLong read(String kind, String ref) {
        if (ref == null || ref.length() > 30) return OptionalLong.empty();
        int dash = ref.indexOf('-');
        if (dash < 1) return OptionalLong.empty();
        var id = ref.substring(0, dash);
        if (!id.matches("[0-9a-z]{1,13}") || !Hmacs.constantTimeEquals(sign(kind, id), ref.substring(dash + 1))) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(id, 36));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private String sign(String kind, String id) {
        var mac = Hmacs.sha256(key, (kind + ":" + id).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(mac, BYTES));
    }
}
