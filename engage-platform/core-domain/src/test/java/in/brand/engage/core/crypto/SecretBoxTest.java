package in.brand.engage.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    static final byte[] KEY = SecretBox.keyFromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    @Test void round_trips() {
        var plain = "totp-secret".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, SecretBox.decrypt(KEY, SecretBox.encrypt(KEY, plain)));
    }

    @Test void same_plaintext_encrypts_differently_each_time() {
        var plain = "totp-secret".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(
                java.util.Arrays.toString(SecretBox.encrypt(KEY, plain)),
                java.util.Arrays.toString(SecretBox.encrypt(KEY, plain)));
    }

    @Test void a_tampered_box_fails_rather_than_returning_garbage() {
        var box = SecretBox.encrypt(KEY, "x".getBytes(StandardCharsets.UTF_8));
        box[box.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> SecretBox.decrypt(KEY, box));
    }

    @Test void a_wrong_key_fails() {
        var box = SecretBox.encrypt(KEY, "x".getBytes(StandardCharsets.UTF_8));
        var other = SecretBox.keyFromHex("ff".repeat(32));
        assertThrows(IllegalStateException.class, () -> SecretBox.decrypt(other, box));
    }

    @Test void key_must_be_32_bytes_of_hex() {
        assertThrows(IllegalArgumentException.class, () -> SecretBox.keyFromHex("abcd"));
        assertThrows(IllegalArgumentException.class, () -> SecretBox.keyFromHex("zz".repeat(32)));
    }
}
