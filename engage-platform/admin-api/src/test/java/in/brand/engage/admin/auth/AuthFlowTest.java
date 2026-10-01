package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.core.crypto.SecretBox;
import in.brand.engage.core.crypto.Totp;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Task 7: login, MFA, refresh, logout and me, against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class AuthFlowTest {

    /** Same value build.gradle.kts gives ADMIN_MFA_KEY in tests. */
    static final byte[] KEY = SecretBox.keyFromHex("0123456789abcdef".repeat(4));

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;

    @BeforeEach void reset() {
        data.cleanAdminTables();
    }

    Map<?, ?> post(String path, Map<String, ?> body) {
        return client.toBlocking().retrieve(HttpRequest.POST(path, body), Map.class);
    }

    HttpResponse<Map> exchange(HttpRequest<?> request) {
        return client.toBlocking().exchange(request, Map.class);
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Test void viewer_without_mfa_logs_in_with_a_password() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var body = post("/api/auth/login", Map.of("email", "viewer@example.com", "password", "hunter2hunter2"));
        assertNotNull(body.get("accessToken"));
    }

    @Test void wrong_password_and_unknown_email_look_identical() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        assertEquals(401, status(() -> post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "wrong"))));
        assertEquals(401, status(() -> post("/api/auth/login",
                Map.of("email", "nobody@example.com", "password", "wrong"))));
    }

    @Test void five_failures_lock_the_account_even_with_the_right_password() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        for (int i = 0; i < 5; i++) {
            status(() -> post("/api/auth/login", Map.of("email", "viewer@example.com", "password", "wrong")));
        }
        assertEquals(401, status(() -> post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2"))));
    }

    @Test void an_operator_above_analyst_must_complete_mfa() {
        var id = data.createOperator("admin@example.com", "hunter2hunter2", "CONFIG_ADMIN");
        var secret = Totp.generateSecret();
        data.enrolMfa(id, SecretBox.encrypt(KEY, secret));

        var first = post("/api/auth/login", Map.of("email", "admin@example.com", "password", "hunter2hunter2"));
        assertEquals(Boolean.TRUE, first.get("mfaRequired"));
        assertNull(first.get("accessToken"), "no access token before the second factor");

        var second = post("/api/auth/mfa", Map.of(
                "mfaToken", first.get("mfaToken"),
                "code", Totp.code(secret, Instant.now())));
        assertNotNull(second.get("accessToken"));
    }

    @Test void a_wrong_totp_code_is_rejected() {
        var id = data.createOperator("admin@example.com", "hunter2hunter2", "CONFIG_ADMIN");
        data.enrolMfa(id, SecretBox.encrypt(KEY, Totp.generateSecret()));
        var first = post("/api/auth/login", Map.of("email", "admin@example.com", "password", "hunter2hunter2"));
        assertEquals(401, status(() -> post("/api/auth/mfa",
                Map.of("mfaToken", first.get("mfaToken"), "code", "000000"))));
    }

    @Test void a_role_above_analyst_without_mfa_cannot_log_in_at_all() {
        data.createOperator("admin@example.com", "hunter2hunter2", "CAMPAIGN_SEND");
        assertEquals(403, status(() -> post("/api/auth/login",
                Map.of("email", "admin@example.com", "password", "hunter2hunter2"))));
    }

    @Test void me_requires_a_valid_token() {
        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me"), Map.class)));
        assertEquals(401, status(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").bearerAuth("not.a.jwt"), Map.class)));
    }

    @Test void me_returns_the_operator_for_a_valid_token() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var token = (String) post("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")).get("accessToken");
        var me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(token), Map.class);
        assertEquals("viewer@example.com", me.get("email"));
        assertEquals(List.of("VIEWER"), me.get("roles"));
        assertEquals(Boolean.FALSE, me.get("mfaEnrolled"));
    }

    @Test void the_same_totp_code_cannot_be_used_twice() {
        var id = data.createOperator("admin@example.com", "hunter2hunter2", "CONFIG_ADMIN");
        var secret = Totp.generateSecret();
        data.enrolMfa(id, SecretBox.encrypt(KEY, secret));
        var code = Totp.code(secret, Instant.now());

        var first = post("/api/auth/login", Map.of("email", "admin@example.com", "password", "hunter2hunter2"));
        assertNotNull(post("/api/auth/mfa", Map.of("mfaToken", first.get("mfaToken"), "code", code)).get("accessToken"));

        // A replayed code is still inside its 30-second window, so only a
        // used-code guard stops an attacker who shoulder-surfed it.
        var second = post("/api/auth/login", Map.of("email", "admin@example.com", "password", "hunter2hunter2"));
        assertEquals(401, status(() -> post("/api/auth/mfa",
                Map.of("mfaToken", second.get("mfaToken"), "code", code))));
    }

    @Test void login_writes_an_audit_row() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        post("/api/auth/login", Map.of("email", "viewer@example.com", "password", "hunter2hunter2"));
        assertEquals(1L, data.countAudit("auth.login"));
    }

    @Test void refresh_rotates_the_cookie_and_the_old_one_then_fails() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var login = exchange(HttpRequest.POST("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")));
        var first = login.getCookie(AuthController.REFRESH_COOKIE).orElseThrow();
        assertTrue(first.isHttpOnly(), "the refresh cookie is invisible to scripts");

        var refreshed = exchange(HttpRequest.POST("/api/auth/refresh", "").cookie(Cookie.of(first.getName(), first.getValue())));
        assertNotNull(refreshed.body().get("accessToken"));
        var second = refreshed.getCookie(AuthController.REFRESH_COOKIE).orElseThrow();
        assertNotEquals(first.getValue(), second.getValue());

        assertEquals(401, status(() -> exchange(HttpRequest.POST("/api/auth/refresh", "")
                .cookie(Cookie.of(first.getName(), first.getValue())))), "a rotated token is spent");
        assertEquals(401, status(() -> exchange(HttpRequest.POST("/api/auth/refresh", "")
                .cookie(Cookie.of(second.getName(), second.getValue())))), "and its reuse ended the whole family");
    }

    @Test void logout_ends_the_session_for_the_access_token_too() {
        data.createOperator("viewer@example.com", "hunter2hunter2", "VIEWER");
        var login = exchange(HttpRequest.POST("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "hunter2hunter2")));
        var token = (String) login.body().get("accessToken");
        var cookie = login.getCookie(AuthController.REFRESH_COOKIE).orElseThrow();

        assertEquals(204, exchange(HttpRequest.POST("/api/auth/logout", "")
                .cookie(Cookie.of(cookie.getName(), cookie.getValue()))).code());

        assertEquals(401, status(() -> client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").bearerAuth(token), Map.class)), "the revoked session's token is dead");
        assertEquals(1L, data.countAudit("auth.logout"));
    }
}
