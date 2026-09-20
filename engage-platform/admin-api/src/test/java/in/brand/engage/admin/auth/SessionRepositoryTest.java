package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class SessionRepositoryTest {

    @Inject SessionRepository sessions;
    @Inject OperatorRepository operators;
    @Inject AdminTestData data;

    UUID operatorId;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        operatorId = data.createOperator("op@example.com", "pw", "VIEWER");
    }

    @Test void a_refresh_token_can_be_rotated_once() {
        var first = sessions.issue(operatorId, UUID.randomUUID(), "test");
        var second = sessions.rotate(first.refreshToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
    }

    @Test void reusing_a_rotated_token_revokes_the_whole_family() {
        var family = UUID.randomUUID();
        var first = sessions.issue(operatorId, family, "test");
        var second = sessions.rotate(first.refreshToken());

        assertThrows(SessionRepository.AuthFailure.class, () -> sessions.rotate(first.refreshToken()));
        // The legitimate client's token dies with the family: both parties must re-authenticate.
        assertThrows(SessionRepository.AuthFailure.class, () -> sessions.rotate(second.refreshToken()));
    }

    @Test void an_unknown_token_is_rejected() {
        assertThrows(SessionRepository.AuthFailure.class, () -> sessions.rotate("not-a-token"));
    }

    @Test void a_revoked_family_cannot_be_rotated() {
        var family = UUID.randomUUID();
        var issued = sessions.issue(operatorId, family, "test");
        sessions.revokeFamily(family, "test");
        assertThrows(SessionRepository.AuthFailure.class, () -> sessions.rotate(issued.refreshToken()));
    }

    @Test void the_stored_value_is_a_hash_not_the_token() {
        var issued = sessions.issue(operatorId, UUID.randomUUID(), "test");
        assertEquals(0L, data.countSessionsWithRawToken(issued.refreshToken()),
                "refresh_hash must be sha256(token), never the token itself");
    }

    @Test void five_failed_logins_lock_the_account() {
        for (int i = 0; i < 5; i++) operators.recordFailedLogin(operatorId);
        var operator = operators.findById(operatorId).orElseThrow();
        assertNotNull(operator.lockedUntil());
        assertTrue(operator.lockedUntil().isAfter(java.time.OffsetDateTime.now()));
    }
}
