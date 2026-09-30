package in.brand.engage.ingest.push;

import io.micronaut.serde.annotation.Serdeable;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bodies posted by the storefront (engage-push.js and the service worker).
 * Every field is untrusted: only the App Proxy's signed query parameters are.
 * Each record's {@code validate()} normalises and rejects with {@link BadRequest}.
 */
public final class StorefrontRequests {

    private StorefrontRequests() {}

    static final Set<String> PERMISSION_SURFACES = Set.of("add_to_cart", "notify_me", "thank_you", "settings", "browse");
    static final Set<String> PROMPT_SURFACES = Set.of("add_to_cart", "notify_me", "thank_you", "cart", "browse");
    static final Set<String> PROMPT_STEPS = Set.of(
            "soft_shown", "soft_accepted", "soft_dismissed",
            "native_granted", "native_denied", "native_dismissed",
            "token_minted", "token_failed", "ios_redirected_to_whatsapp");
    static final Set<String> PLATFORMS = Set.of("WEB", "IOS_WEB");
    static final Set<String> BROWSERS = Set.of("chrome", "edge", "firefox", "samsung", "safari", "other");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Serdeable
    public record Register(String anonId, String token, String surface, String platform, String browser,
                           String copyVersion, String copyText, String page, String cartToken) {
        public Register validate() {
            return new Register(anon(anonId), required(token, "token", 4096), oneOf(surface, "surface", PERMISSION_SURFACES),
                    platform == null ? "WEB" : oneOf(platform, "platform", PLATFORMS),
                    knownBrowser(browser),
                    required(copyVersion, "copyVersion", 64), required(copyText, "copyText", 1000),
                    optional(page, "page", 512), optional(cartToken, "cartToken", 256));
        }
    }

    @Serdeable
    public record Refresh(String anonId, String token, String previous) {
        public Refresh validate() {
            var prev = optional(previous, "previous", 4096);
            return new Refresh(anon(anonId), required(token, "token", 4096), token.equals(prev) ? null : prev);
        }
    }

    @Serdeable
    public record Unregister(String anonId, String token) {
        public Unregister validate() {
            return new Unregister(anon(anonId), required(token, "token", 4096));
        }
    }

    @Serdeable
    /** {@code reason}: why a token_failed happened (an error code). Logged, never stored. */
    public record PromptEvent(String anonId, String surface, String step, String platform, String browser,
                              String reason) {
        public PromptEvent validate() {
            return new PromptEvent(anon(anonId), oneOf(surface, "surface", PROMPT_SURFACES), oneOf(step, "step", PROMPT_STEPS),
                    platform == null ? null : oneOf(platform, "platform", PLATFORMS),
                    knownBrowser(browser), reason == null ? null : truncate(reason.replaceAll("[^\\w:.,/ -]", "?"), 200));
        }
    }

    @Serdeable
    public record Cart(String anonId, String cartToken) {
        public Cart validate() {
            return new Cart(anon(anonId), required(cartToken, "cartToken", 256));
        }
    }

    @Serdeable
    public record NotifyMe(String anonId, String variantId, String productHandle, String sizeLabel) {
        public NotifyMe validate() {
            return new NotifyMe(anon(anonId), required(variantId, "variantId", 64),
                    required(productHandle, "productHandle", 255), optional(sizeLabel, "sizeLabel", 64));
        }
    }

    @Serdeable
    /** @param sig the push's signature of {@code sid} (BeaconSignature); unsigned beacons are ignored */
    public record Engagement(String kind, String sid, String sig, String sw) {
        public Engagement validate() {
            return new Engagement(oneOf(kind, "kind", Set.of("impression", "click")),
                    optional(sid, "sid", 64), optional(sig, "sig", 64), optional(sw, "sw", 32));
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    /** Known browser names only; anything else (or nothing) is recorded as unknown. */
    private static String knownBrowser(String v) {
        return v != null && BROWSERS.contains(v) ? v : null;
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String anon(String v) {
        if (v == null || !UUID_TEXT.matcher(v).matches()) throw new BadRequest("anonId must be a UUID");
        return v.toLowerCase(java.util.Locale.ROOT);
    }

    private static String required(String v, String field, int max) {
        if (v == null || v.isBlank()) throw new BadRequest(field + " is required");
        return optional(v, field, max);
    }

    private static String optional(String v, String field, int max) {
        if (v == null || v.isBlank()) return null;
        var t = v.strip();
        if (t.length() > max) throw new BadRequest(field + " is longer than " + max);
        return t;
    }

    private static String oneOf(String v, String field, Set<String> allowed) {
        if (v == null || !allowed.contains(v)) throw new BadRequest(field + " is not one of " + allowed);
        return v;
    }

    public static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadRequest(String message) {
            super(message);
        }
    }
}
