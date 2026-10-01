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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Task 8: MFA enrolment, set-password links, and the bootstrap owner's first sign-in. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class EnrolmentTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Tokens tokens;
    @Inject OperatorRepository operators;
    @Inject PasswordHasher hasher;

    @BeforeEach void reset() {
        data.cleanAdminTables();
    }

    String link(java.util.UUID id) {
        var operator = operators.findById(id).orElseThrow();
        return tokens.issuePurpose(id, "set-password", Map.of("pwd", String.valueOf(operator.ver())), Duration.ofHours(1));
    }

    Map<?, ?> post(String path, Object body) {
        return client.toBlocking().retrieve(HttpRequest.POST(path, body), Map.class);
    }

    HttpClientResponseException fails(Runnable call) {
        return assertThrows(HttpClientResponseException.class, call::run);
    }

    @Test void a_set_password_link_works_once() {
        var id = data.createOperator("new@example.com", "placeholder-unusable", "VIEWER");
        var link = link(id);

        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link, "password", "a-brand-new-password")));

        // The same link again must fail: setting the password moved password_changed_at.
        assertEquals(401, fails(() -> client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link, "password", "another-password-x"))))
                .getStatus().getCode());

        assertNotNull(post("/api/auth/login",
                Map.of("email", "new@example.com", "password", "a-brand-new-password")).get("accessToken"));
        assertEquals(1L, data.countAudit("auth.password_set"));
    }

    @Test void a_short_password_is_refused_and_the_link_stays_usable() {
        var id = data.createOperator("new@example.com", "placeholder-unusable", "VIEWER");
        var link = link(id);
        assertEquals(400, fails(() -> client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link, "password", "short")))).getStatus().getCode());
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link, "password", "long-enough-password")));
    }

    @Test void enrolment_returns_a_provisioning_uri_and_confirm_activates_mfa() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var token = (String) post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")).get("accessToken");

        var enrol = client.toBlocking().retrieve(HttpRequest.POST("/api/auth/mfa/enrol", Map.of()).bearerAuth(token), Map.class);
        var uri = (String) enrol.get("provisioningUri");
        assertTrue(uri.startsWith("otpauth://totp/Engage:"), uri);

        var secret = Base64.getDecoder().decode((String) enrol.get("secret"));
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/mfa/confirm",
                Map.of("code", Totp.code(secret, Instant.now()))).bearerAuth(token));

        var me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(token), Map.class);
        assertEquals(Boolean.TRUE, me.get("mfaEnrolled"));
        assertEquals(1L, data.countAudit("auth.mfa_enrolled"));
    }

    @Test void a_staged_secret_is_not_mfa_until_confirmed() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var token = (String) post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")).get("accessToken");
        client.toBlocking().retrieve(HttpRequest.POST("/api/auth/mfa/enrol", Map.of()).bearerAuth(token), Map.class);

        assertEquals(401, fails(() -> client.toBlocking().exchange(HttpRequest.POST("/api/auth/mfa/confirm",
                Map.of("code", "000000")).bearerAuth(token))).getStatus().getCode());
        var me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(token), Map.class);
        assertEquals(Boolean.FALSE, me.get("mfaEnrolled"));
    }

    @Test void changing_the_password_invalidates_outstanding_access_tokens() {
        var id = data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var token = (String) post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")).get("accessToken");

        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link(id), "password", "brand-new-password")));

        assertEquals(401, fails(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").bearerAuth(token), Map.class)).getStatus().getCode());
    }

    @Test void a_new_owner_goes_from_the_link_to_a_signed_in_session() {
        var id = operators.createInvitedOwner("owner@example.com", "Owner",
                hasher.hash("placeholder-unusable".toCharArray()));
        assertEquals(401, fails(() -> post("/api/auth/login",
                Map.of("email", "owner@example.com", "password", "placeholder-unusable"))).getStatus().getCode(),
                "an invited owner cannot sign in before setting a password");

        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", link(id), "password", "owner-chosen-password")));

        // Password alone is not enough for an OWNER: 403 with an enrolment-only token.
        var denied = fails(() -> post("/api/auth/login",
                Map.of("email", "owner@example.com", "password", "owner-chosen-password")));
        assertEquals(403, denied.getStatus().getCode());
        var enrolToken = (String) denied.getResponse().getBody(Map.class).orElseThrow().get("enrolToken");
        assertNotNull(enrolToken);

        var enrol = post("/api/auth/mfa/enrol", Map.of("enrolToken", enrolToken));
        var secret = Base64.getDecoder().decode((String) enrol.get("secret"));
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/mfa/confirm",
                Map.of("code", Totp.code(secret, Instant.now()), "enrolToken", enrolToken)));

        // Now: password, then the next code (the confirm code is spent).
        var challenge = post("/api/auth/login", Map.of("email", "owner@example.com", "password", "owner-chosen-password"));
        assertEquals(Boolean.TRUE, challenge.get("mfaRequired"));
        var signed = post("/api/auth/mfa", Map.of("mfaToken", challenge.get("mfaToken"),
                "code", Totp.code(secret, Instant.now().plusSeconds(30))));
        assertNotNull(signed.get("accessToken"));
        assertEquals(java.util.List.of("OWNER"), ((Map<?, ?>) signed.get("operator")).get("roles"));
    }

    @Test void an_enrolment_token_cannot_be_used_as_a_session() {
        data.createOperator("admin@example.com", "hunter2hunter2", "CONFIG_ADMIN");
        var denied = fails(() -> post("/api/auth/login", Map.of("email", "admin@example.com", "password", "hunter2hunter2")));
        var enrolToken = (String) denied.getResponse().getBody(Map.class).orElseThrow().get("enrolToken");

        assertEquals(401, fails(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").bearerAuth(enrolToken), Map.class)).getStatus().getCode());
    }
}
