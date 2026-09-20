package in.brand.engage.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers shared by every webhook verifier. */
public final class Hmacs {

    private Hmacs() {}

    public static byte[] sha256(String secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String sha256Hex(String secret, byte[] message) {
        return HexFormat.of().formatHex(sha256(secret, message));
    }

    public static String sha256Base64(String secret, byte[] message) {
        return Base64.getEncoder().encodeToString(sha256(secret, message));
    }

    /**
     * Constant-time comparison. A plain equals() returns early on the first
     * mismatching character, which leaks how much of a forged signature was
     * right through response timing.
     */
    public static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
