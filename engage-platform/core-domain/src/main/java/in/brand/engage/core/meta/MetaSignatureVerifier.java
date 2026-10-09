package in.brand.engage.core.meta;

import in.brand.engage.core.crypto.Hmacs;

/**
 * Meta webhook signatures: {@code X-Hub-Signature-256: sha256=<hex HMAC-SHA256>}
 * of the raw body, keyed with the <b>app secret</b> (App settings → Basic),
 * not the WhatsApp access token. Verified over the exact bytes received:
 * re-serialised JSON would not match.
 *
 * <p>An empty secret verifies nothing: every webhook is refused until
 * {@code WA_APP_SECRET} is set.
 */
public final class MetaSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final String appSecret;

    public MetaSignatureVerifier(String appSecret) {
        this.appSecret = appSecret == null ? "" : appSecret;
    }

    public boolean configured() {
        return !appSecret.isBlank();
    }

    public boolean verify(byte[] rawBody, String header) {
        if (!configured() || rawBody == null || header == null) return false;
        var h = header.trim();
        if (!h.startsWith(PREFIX)) return false;
        return Hmacs.constantTimeEquals(Hmacs.sha256Hex(appSecret, rawBody), h.substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT));
    }
}
