package in.brand.engage.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnsubscribeTokenTest {

    final UnsubscribeToken tokens = UnsubscribeToken.fromAppSecret("test-app-secret");

    @Test void a_token_names_its_person_and_is_url_safe() {
        var id = UUID.randomUUID();
        var t = tokens.of(id);
        assertEquals(Optional.of(id), tokens.parse(t));
        assertTrue(t.matches("[A-Za-z0-9_-]{44}"), t);
    }

    @Test void someone_else_cannot_be_unsubscribed_by_guessing() {
        var t = tokens.of(UUID.randomUUID());
        var other = tokens.of(UUID.randomUUID());
        assertTrue(tokens.parse(other.substring(0, 22) + t.substring(22)).isEmpty(), "another person's id with this signature");
        assertTrue(tokens.parse(UnsubscribeToken.fromAppSecret("another").of(UUID.randomUUID())).isEmpty());
        for (var bad : new String[] {null, "", "x", "!".repeat(44)}) assertTrue(tokens.parse(bad).isEmpty());
    }
}
