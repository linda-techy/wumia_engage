package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    final PasswordHasher hasher = new PasswordHasher();

    @Test void verifies_the_password_it_hashed() {
        var stored = hasher.hash("correct horse battery staple".toCharArray());
        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), stored));
    }

    @Test void rejects_a_wrong_password() {
        var stored = hasher.hash("correct horse".toCharArray());
        assertFalse(hasher.verify("correct horsf".toCharArray(), stored));
    }

    @Test void same_password_hashes_differently_each_time() {
        var a = hasher.hash("same".toCharArray());
        var b = hasher.hash("same".toCharArray());
        assertNotEquals(a, b, "a per-hash salt must make these differ");
        assertTrue(hasher.verify("same".toCharArray(), a));
        assertTrue(hasher.verify("same".toCharArray(), b));
    }

    @Test void stored_format_names_argon2id_and_its_parameters() {
        assertTrue(hasher.hash("x".toCharArray()).startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));
    }

    @Test void a_malformed_stored_value_is_false_not_an_exception() {
        assertFalse(hasher.verify("x".toCharArray(), "not-a-hash"));
        assertFalse(hasher.verify("x".toCharArray(), ""));
    }
}
