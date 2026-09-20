package in.brand.engage.admin.auth;

import jakarta.inject.Singleton;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id, 64 MiB / 3 passes / 1 lane. Memory-hard by design: the cost of a
 * stolen password_hash column is GPU-hours per guess, not microseconds.
 */
@Singleton
public class PasswordHasher {

    private static final int MEMORY_KB = 65536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int HASH_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "$argon2id$v=19$m=%d,t=%d,p=%d$".formatted(MEMORY_KB, ITERATIONS, PARALLELISM);

    private final SecureRandom random = new SecureRandom();

    public String hash(char[] password) {
        var salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        var hash = derive(password, salt);
        var b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean verify(char[] password, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return false;
        var parts = stored.substring(PREFIX.length()).split("\\$");
        if (parts.length != 2) return false;
        try {
            var b64 = Base64.getDecoder();
            var salt = b64.decode(parts[0]);
            var expected = b64.decode(parts[1]);
            // Constant-time: a timing difference here leaks how much of the hash matched.
            return MessageDigest.isEqual(derive(password, salt), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt) {
        var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();
        var generator = new Argon2BytesGenerator();
        generator.init(params);
        var bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        var passwordBytes = new byte[bytes.remaining()];
        bytes.get(passwordBytes);
        var out = new byte[HASH_BYTES];
        try {
            generator.generateBytes(passwordBytes, out);
            return out;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }
}
