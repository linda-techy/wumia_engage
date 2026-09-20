package in.brand.engage.core.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for small secrets at rest (MFA seeds). Layout: 12-byte nonce
 * followed by ciphertext+tag. GCM authenticates, so a tampered box fails to
 * decrypt rather than yielding a plausible wrong value.
 */
public final class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecretBox() {}

    public static byte[] keyFromHex(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("key must be 64 hex characters (32 bytes)");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("key must be hex", e);
        }
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        var nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            var sealed = cipher.doFinal(plaintext);
            var out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] box) {
        if (box == null || box.length <= NONCE_BYTES) throw new IllegalStateException("box too short");
        var nonce = Arrays.copyOfRange(box, 0, NONCE_BYTES);
        var sealed = Arrays.copyOfRange(box, NONCE_BYTES, box.length);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}
