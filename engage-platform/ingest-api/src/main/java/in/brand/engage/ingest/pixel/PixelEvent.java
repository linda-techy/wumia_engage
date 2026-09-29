package in.brand.engage.ingest.pixel;

import in.brand.engage.ingest.push.StorefrontRequests.BadRequest;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One customer event from the web pixel (phase-2 §7). Every field is
 * untrusted and none names a person: the pixel sends a checkout token, a
 * product and a timestamp, never an email, phone or address. Fields not
 * listed here are dropped by deserialisation, so nothing else can be stored.
 */
@Serdeable
public record PixelEvent(String name, String clientId, String at, String anonId, String checkoutToken,
                         Long totalPaise, String productId, String variantId, String productTitle,
                         String productHandle, Long pricePaise) {

    /** Checkout progress, in the order a shopper moves through it. */
    public static final Set<String> CHECKOUT_STEPS = Set.of(
            "checkout_started", "checkout_contact_info_submitted", "checkout_shipping_info_submitted",
            "payment_info_submitted", "checkout_completed");
    /** Storefront browsing, for browse_abandon (P3-T07). */
    public static final Set<String> BROWSING = Set.of("product_viewed", "product_added_to_cart");

    private static final long MAX_PAISE = 10_000_000_000L;   // ₹10 crore: anything above is not a real basket
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern HANDLE = Pattern.compile("[\\p{L}\\p{N}_-]{1,255}");

    public record Valid(String name, String clientId, Instant at, String anonId, String checkoutToken,
                        Long totalPaise, String productId, String variantId, String productTitle,
                        String productHandle, Long pricePaise) {
        public boolean isCheckoutStep() {
            return CHECKOUT_STEPS.contains(name);
        }
    }

    public Valid validate() {
        if (name == null || !(CHECKOUT_STEPS.contains(name) || BROWSING.contains(name))) {
            throw new BadRequest("name is not an accepted event");
        }
        if (clientId == null || clientId.isBlank() || clientId.length() > 128) throw new BadRequest("clientId is required");
        Instant when;
        try {
            when = Instant.parse(at == null ? "" : at);
        } catch (DateTimeParseException e) {
            throw new BadRequest("at must be an ISO-8601 instant");
        }
        var checkout = optional(checkoutToken, 256);
        if (CHECKOUT_STEPS.contains(name) && checkout == null) throw new BadRequest("checkoutToken is required");
        var product = id(productId, "productId");
        if (BROWSING.contains(name) && product == null) throw new BadRequest("productId is required");
        return new Valid(name, clientId.strip(), when,
                anonId != null && UUID_TEXT.matcher(anonId).matches() ? anonId : null,   // someone else's id is harmless, junk is dropped
                checkout, paise(totalPaise), product, id(variantId, "variantId"), optional(productTitle, 255),
                productHandle != null && HANDLE.matcher(productHandle).matches() ? productHandle : null,
                paise(pricePaise));
    }

    private static String optional(String v, int max) {
        if (v == null || v.isBlank()) return null;
        var t = v.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String id(String v, String field) {
        if (v == null || v.isBlank()) return null;
        if (!ID.matcher(v).matches()) throw new BadRequest(field + " must be a numeric id");
        return v;
    }

    private static Long paise(Long v) {
        return v == null || v < 0 || v > MAX_PAISE ? null : v;
    }
}
