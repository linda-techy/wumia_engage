package in.brand.engage.core.razorpay;

import in.brand.engage.core.crypto.Hmacs;

/**
 * Verifies {@code X-Razorpay-Signature}: hex HMAC-SHA256 of the RAW request
 * body, keyed with the WEBHOOK secret — the value you typed when creating the
 * webhook in the Razorpay Dashboard. It is not the API key secret; mixing the
 * two up is the most common reason every check fails.
 */
public final class RazorpaySignatureVerifier {

    private final String webhookSecret;

    public RazorpaySignatureVerifier(String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("Razorpay webhook secret not set");
        }
        this.webhookSecret = webhookSecret;
    }

    public boolean verify(byte[] rawBody, String headerSignatureHex) {
        if (rawBody == null || headerSignatureHex == null || headerSignatureHex.isBlank()) return false;
        return Hmacs.constantTimeEquals(Hmacs.sha256Hex(webhookSecret, rawBody), headerSignatureHex.trim());
    }
}
