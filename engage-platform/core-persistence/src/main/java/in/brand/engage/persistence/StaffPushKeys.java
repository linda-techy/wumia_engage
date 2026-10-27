package in.brand.engage.persistence;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;

/**
 * The VAPID key pair (RFC 8292) behind staff alerts (V50). Created on first
 * use and kept in {@code staff_push_keys}: the console subscribes with the
 * public key, the worker signs each alert with the private one. Alerts carry
 * no payload, so no message encryption (RFC 8291) is involved.
 */
public final class StaffPushKeys {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /** @param publicKey uncompressed P-256 point, base64url (what PushManager.subscribe takes) */
    public record Keys(String publicKey, PrivateKey privateKey) {

        /**
         * The {@code Authorization} header for one push service.
         *
         * @param audience the push service origin, e.g. {@code https://fcm.googleapis.com}
         */
        public String authorization(String audience, String subject, Instant now) {
            var header = URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            var claims = URL.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + now.plusSeconds(12 * 3600).getEpochSecond()
                    + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
            var signingInput = header + "." + claims;
            try {
                var sig = Signature.getInstance("SHA256withECDSAinP1363Format");
                sig.initSign(privateKey);
                sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return "vapid t=" + signingInput + "." + URL.encodeToString(sig.sign()) + ", k=" + publicKey;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("cannot sign a VAPID token", e);
            }
        }
    }

    private StaffPushKeys() {}

    /** The pair, created and stored the first time it is needed (racing callers keep the first insert). */
    public static Keys load(Connection c) throws SQLException {
        var existing = read(c);
        if (existing != null) return existing;
        var generated = generate();
        Sql.update(c, "INSERT INTO staff_push_keys (id, public_key, private_key) VALUES (1, ?, ?) ON CONFLICT (id) DO NOTHING",
                generated[0], generated[1]);
        return read(c);
    }

    private static Keys read(Connection c) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT public_key, private_key FROM staff_push_keys WHERE id = 1"); var rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            try {
                var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(rs.getString(2))));
                return new Keys(rs.getString(1), key);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("staff_push_keys holds an unreadable private key", e);
            }
        }
    }

    /** @return {public key base64url, private key PKCS#8 base64} */
    static String[] generate() {
        try {
            var gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = gen.generateKeyPair();
            var w = ((ECPublicKey) pair.getPublic()).getW();
            var point = new byte[65];
            point[0] = 0x04;
            copy32(w.getAffineX(), point, 1);
            copy32(w.getAffineY(), point, 33);
            return new String[] {URL.encodeToString(point), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate a P-256 key pair", e);
        }
    }

    private static void copy32(BigInteger v, byte[] out, int offset) {
        var b = v.toByteArray();
        int start = Math.max(0, b.length - 32);
        int len = b.length - start;
        System.arraycopy(b, start, out, offset + 32 - len, len);
    }
}
