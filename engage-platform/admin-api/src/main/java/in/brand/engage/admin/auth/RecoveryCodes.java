package in.brand.engage.admin.auth;

import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * MFA recovery codes (P6-T08): ten per operator, issued when MFA is
 * confirmed, shown once, each single-use. {@code XXXXX-XXXXX} in Crockford
 * base32, 50 random bits; only the sha256 is stored. Issuing a new set
 * replaces the old one.
 */
@Singleton
public class RecoveryCodes {

    static final int COUNT = 10;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private final SecureRandom random = new SecureRandom();

    /** Replaces the operator's codes with ten new ones and returns them, for showing once. */
    public List<String> issue(Connection c, UUID operatorId) throws SQLException {
        Sql.update(c, "DELETE FROM operator_recovery_codes WHERE operator_id = ?", operatorId);
        var codes = new ArrayList<String>(COUNT);
        while (codes.size() < COUNT) {
            var sb = new StringBuilder(11);
            for (int i = 0; i < 10; i++) {
                if (i == 5) sb.append('-');
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            var code = sb.toString();
            if (codes.contains(code)) continue;
            codes.add(code);
            Sql.update(c, "INSERT INTO operator_recovery_codes (operator_id, code_hash) VALUES (?, ?)",
                    operatorId, hash(normalise(code)));
        }
        return List.copyOf(codes);
    }

    /** Spends a code. @return false when it is not one of theirs, or was used already */
    public boolean consume(Connection c, UUID operatorId, String code) throws SQLException {
        var n = normalise(code);
        if (n == null) return false;
        return Sql.update(c, """
                UPDATE operator_recovery_codes SET used_at = now()
                 WHERE operator_id = ? AND code_hash = ? AND used_at IS NULL""", operatorId, hash(n)) == 1;
    }

    /** What looks like a recovery code rather than a 6-digit TOTP. */
    static boolean looksLikeOne(String code) {
        return normalise(code) != null;
    }

    /** Upper case, without dashes and spaces; Crockford's I/L → 1 and O → 0. Null if it cannot be a code. */
    static String normalise(String code) {
        if (code == null) return null;
        var s = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT)
                .replace('I', '1').replace('L', '1').replace('O', '0');
        if (s.length() != 10) return null;
        for (int i = 0; i < s.length(); i++) if (ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        return s;
    }

    private static byte[] hash(String normalised) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
