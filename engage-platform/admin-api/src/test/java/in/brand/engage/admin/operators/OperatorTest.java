package in.brand.engage.admin.operators;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T01: operator management (OWNER only), the per-IP sign-in limit and JWKS. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class OperatorTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject in.brand.engage.admin.auth.LoginRateLimit loginLimit;

    String owner;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        loginLimitReset();
        owner = data.loginWithMfa(client, "owner@example.com", "OWNER");
    }

    void loginLimitReset() {
        try {
            var m = loginLimit.getClass().getDeclaredMethod("reset");
            m.setAccessible(true);
            m.invoke(loginLimit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    Map<String, Object> invite(String email, List<String> roles) {
        return client.toBlocking().retrieve(HttpRequest.POST("/api/operators",
                Map.of("email", email, "fullName", "New Person", "roles", roles)).bearerAuth(owner), Map.class);
    }

    String tokenFromLink(Object link) {
        var s = (String) link;
        return URLDecoder.decode(s.substring(s.indexOf("token=") + 6), StandardCharsets.UTF_8);
    }

    @Test void an_invited_operator_sets_a_password_from_the_link_and_signs_in() {
        var invited = invite("analyst@example.com", List.of("ANALYST"));
        assertEquals(1L, data.countAudit("operator.invite"));

        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", tokenFromLink(invited.get("setPasswordLink")), "password", "analyst-password-1")));
        var signed = client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("email", "analyst@example.com", "password", "analyst-password-1")), Map.class);
        assertEquals(List.of("ANALYST"), ((Map<String, Object>) signed.get("operator")).get("roles"));

        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/operators").bearerAuth(owner), Argument.listOf(Map.class));
        var row = ((List<Map<String, Object>>) (List<?>) list).stream()
                .filter(o -> "analyst@example.com".equals(o.get("email"))).findFirst().orElseThrow();
        assertEquals("active", row.get("status"));
        assertNotNull(row.get("lastLoginAt"));
        assertFalse(list.toString().contains("argon2"), "never the password hash");
    }

    @Test void only_an_owner_manages_operators() {
        var configAdmin = data.loginWithMfa(client, "config@example.com", "CONFIG_ADMIN");
        assertEquals(403, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/operators").bearerAuth(configAdmin), String.class)));
        assertEquals(403, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/operators",
                Map.of("email", "x@example.com", "fullName", "X", "roles", List.of("OWNER"))).bearerAuth(configAdmin))));
        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/operators"), String.class)));
    }

    @Test void a_role_change_takes_effect_on_the_next_request_and_is_audited() {
        var invited = invite("viewer@example.com", List.of("VIEWER"));
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", tokenFromLink(invited.get("setPasswordLink")), "password", "viewer-password-1")));
        var viewer = (String) client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("email", "viewer@example.com", "password", "viewer-password-1")), Map.class).get("accessToken");
        var id = (String) invited.get("id");

        client.toBlocking().exchange(HttpRequest.PUT("/api/operators/" + id + "/roles", Map.of("roles", List.of("VIEWER", "ANALYST")))
                .bearerAuth(owner));

        var me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(viewer), Map.class);
        assertEquals(List.of("ANALYST", "VIEWER"), me.get("roles"), "roles are read from the database, not the token");
        assertEquals(1L, data.countAudit("operator.roles"));
    }

    @Test void the_last_owner_cannot_be_demoted_or_disabled_and_no_one_disables_themselves() {
        var me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(owner), Map.class);
        var myId = (String) me.get("id");

        assertEquals(409, status(() -> client.toBlocking().exchange(HttpRequest.PUT("/api/operators/" + myId + "/roles",
                Map.of("roles", List.of("VIEWER"))).bearerAuth(owner))), "the last OWNER keeps OWNER");
        assertEquals(409, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/operators/" + myId + "/disable", Map.of())
                .bearerAuth(owner))), "nobody disables themselves");
    }

    @Test void disabling_ends_the_operators_sessions_at_once() {
        var invited = invite("leaver@example.com", List.of("VIEWER"));
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/set-password",
                Map.of("token", tokenFromLink(invited.get("setPasswordLink")), "password", "leaver-password-1")));
        var leaver = (String) client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("email", "leaver@example.com", "password", "leaver-password-1")), Map.class).get("accessToken");

        client.toBlocking().exchange(HttpRequest.POST("/api/operators/" + invited.get("id") + "/disable", Map.of()).bearerAuth(owner));

        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(leaver), Map.class)));
        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("email", "leaver@example.com", "password", "leaver-password-1")), Map.class)));
        assertEquals(1L, data.countAudit("operator.disable"));
    }

    @Test void a_reset_clears_mfa_ends_sessions_and_gives_a_fresh_link() {
        var admin = data.loginWithMfa(client, "admin@example.com", "CONFIG_ADMIN");
        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/operators").bearerAuth(owner), Argument.listOf(Map.class));
        var adminId = (String) ((List<Map<String, Object>>) (List<?>) list).stream()
                .filter(o -> "admin@example.com".equals(o.get("email"))).findFirst().orElseThrow().get("id");

        var reset = client.toBlocking().retrieve(HttpRequest.POST("/api/operators/" + adminId + "/reset", Map.of()).bearerAuth(owner), Map.class);

        assertEquals(401, status(() -> client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me").bearerAuth(admin), Map.class)));
        assertTrue(((String) reset.get("setPasswordLink")).startsWith("/set-password?token="));
        assertEquals(1L, data.countAudit("operator.reset"));
    }

    @Test void a_duplicate_email_or_unknown_role_is_refused() {
        invite("dup@example.com", List.of("VIEWER"));
        assertEquals(409, status(() -> invite("Dup@Example.com", List.of("VIEWER"))));
        assertEquals(400, status(() -> invite("new@example.com", List.of("SUPERUSER"))));
    }

    @Test void one_address_is_limited_to_30_sign_in_attempts_in_ten_minutes() {
        for (int i = 0; i < 29; i++) {      // the owner's own sign-in in reset() used one
            status(() -> client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                    Map.of("email", "nobody@example.com", "password", "wrong")), Map.class));
        }
        assertEquals(429, status(() -> client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("email", "nobody@example.com", "password", "wrong")), Map.class)));
    }

    @Test void the_public_signing_key_is_published_and_matches_token_headers() {
        var jwks = client.toBlocking().retrieve(HttpRequest.GET("/.well-known/jwks.json"), Map.class);
        var key = ((List<Map<String, Object>>) jwks.get("keys")).getFirst();
        assertEquals("RSA", key.get("kty"));
        assertEquals("RS256", key.get("alg"));
        assertNull(key.get("d"), "never the private exponent");

        var header = new String(java.util.Base64.getUrlDecoder().decode(owner.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"kid\":\"" + key.get("kid") + "\""), header);
    }
}
