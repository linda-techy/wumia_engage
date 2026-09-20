package in.brand.engage.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238), SHA-1, 30-second steps, 6 digits — what Google
 * Authenticator, Authy and 1Password implement. SHA-1 here is HMAC-SHA1,
 * which the collision attacks on SHA-1 do not affect.
 */
public final class Totp {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    public static byte[] generateSecret() {
        var secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    public static String code(byte[] secret, Instant at) {
        long counter = Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
        byte[] mac = hmac(secret, ByteBuffer.allocate(8).putLong(counter).array());
        int offset = mac[mac.length - 1] & 0x0f;
        int binary = ((mac[offset] & 0x7f) << 24)
                | ((mac[offset + 1] & 0xff) << 16)
                | ((mac[offset + 2] & 0xff) << 8)
                | (mac[offset + 3] & 0xff);
        int value = binary % (int) Math.pow(10, DIGITS);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", value);
    }

    /** Accepts the current step plus one either side: phones drift, people are slow. */
    public static boolean verify(byte[] secret, String code, Instant now) {
        if (code == null || code.length() != DIGITS) return false;
        for (int step = -1; step <= 1; step++) {
            var candidate = code(secret, now.plusSeconds((long) step * STEP_SECONDS));
            // Constant-time compare: this runs on attacker-supplied input.
            if (java.security.MessageDigest.isEqual(
                    candidate.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    public static String provisioningUri(String issuer, String account, byte[] secret) {
        var label = java.net.URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label.replace("%3A", ":")
                + "?secret=" + base32(secret)
                + "&issuer=" + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public static String base32(byte[] data) {
        var out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        return out.toString();
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }
}
