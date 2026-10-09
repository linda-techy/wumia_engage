package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.core.crypto.Totp;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T08: ten single-use recovery codes, issued at MFA confirmation. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class RecoveryCodeTest {

    static final String EMAIL = "phone-lost@example.com";
    static final String PASSWORD = "phone-lost-password";

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;

    byte[] secret;
    List<String> codes;
    String session;

    @BeforeEach void enrol() {
        data.cleanAdminTables();
        loginLimit.reset();
        data.createOperator(EMAIL, PASSWORD, "ANALYST");
        session = (String) post("/api/auth/login", Map.of("email", EMAIL, "password", PASSWORD), null).get("accessToken");
        var enrolment = post("/api/auth/mfa/enrol", Map.of(), session);
        secret = Base64.getDecoder().decode((String) enrolment.get("secret"));
        var confirmed = post("/api/auth/mfa/confirm", Map.of("code", Totp.code(secret, Instant.now())), session);
        codes = (List<String>) confirmed.get("recoveryCodes");
    }

    Map<String, Object> post(String path, Map<String, ?> body, String token) {
        var req = HttpRequest.POST(path, body);
        if (token != null) req = req.bearerAuth(token);
        return client.toBlocking().retrieve(req, Map.class);
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    String mfaToken() {
        return (String) post("/api/auth/login", Map.of("email", EMAIL, "password", PASSWORD), null).get("mfaToken");
    }

    @Test void confirmation_returns_ten_distinct_codes_once() {
        assertEquals(10, codes.size());
        assertEquals(10, new HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(c -> c.matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}")), codes.toString());
    }

    @Test void a_recovery_code_signs_in_once_in_place_of_the_authenticator() {
        var code = codes.getFirst();
        var signed = post("/api/auth/mfa", Map.of("mfaToken", mfaToken(), "code", code.toLowerCase().replace("-", " ")), null);
        assertNotNull(signed.get("accessToken"), "case and separators do not matter");
        assertEquals(1L, data.countAudit("auth.recovery_code_used"));

        assertEquals(401, status(() -> post("/api/auth/mfa", Map.of("mfaToken", mfaToken(), "code", code), null)),
                "each code works once");
        assertEquals(401, status(() -> post("/api/auth/mfa", Map.of("mfaToken", mfaToken(), "code", "ABCDE-FGHJK"), null)));
    }

    @Test void new_codes_need_a_current_authenticator_code_and_void_the_old_set() {
        assertEquals(401, status(() -> post("/api/auth/mfa/recovery-codes", Map.of("code", "000000"), session)));
        var fresh = (List<String>) post("/api/auth/mfa/recovery-codes",
                Map.of("code", Totp.code(secret, Instant.now().plusSeconds(30))), session).get("recoveryCodes");
        assertEquals(10, fresh.size());
        assertEquals(1L, data.countAudit("auth.recovery_codes_reissued"));

        assertEquals(401, status(() -> post("/api/auth/mfa", Map.of("mfaToken", mfaToken(), "code", codes.getFirst()), null)),
                "the old set is void");
        assertNotNull(post("/api/auth/mfa", Map.of("mfaToken", mfaToken(), "code", fresh.getFirst()), null).get("accessToken"));
    }
}
