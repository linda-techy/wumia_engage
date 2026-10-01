package in.brand.engage.admin.consent;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Task 12: the consent copy registry against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class ConsentCopyTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;

    Map<String, Object> version = Map.of(
            "version", "wa_test_v1",
            "channel", "whatsapp",
            "surface", "cart",
            "text", "Send me order updates and offers from Wumika on WhatsApp",
            "purposes", List.of("transactional", "marketing"));

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.deleteConsentCopy("wa_test_v1");
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Test void a_viewer_cannot_register_a_version() {
        var token = data.loginAsViewer(client);
        assertEquals(403, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", version).bearerAuth(token))));
    }

    @Test void a_config_admin_registers_a_version_and_it_appears_in_the_list() {
        var token = data.loginAsConfigAdmin(client);
        var created = client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", version).bearerAuth(token));
        assertEquals(201, created.getStatus().getCode());

        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/consent-copy").bearerAuth(token),
                io.micronaut.core.type.Argument.listOf(Map.class));
        var row = ((List<Map<String, Object>>) (List<?>) list).stream()
                .filter(v -> "wa_test_v1".equals(v.get("version"))).findFirst().orElseThrow();
        assertEquals(List.of("marketing", "transactional"),
                ((List<String>) row.get("purposes")).stream().sorted().toList());
        assertEquals(0, ((Number) row.get("grants")).intValue());
        assertEquals(1L, data.countAudit("consent_copy.register"));
    }

    @Test void a_campaign_edit_role_is_not_a_config_admin() {
        // Roles are per-endpoint, not a ladder: a campaign role grants nothing here.
        var token = data.loginAsCampaignEditor(client);
        assertEquals(403, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", version).bearerAuth(token))));
    }

    @Test void registering_the_same_version_twice_is_rejected() {
        var token = data.loginAsConfigAdmin(client);
        client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", version).bearerAuth(token));
        assertEquals(409, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", version).bearerAuth(token))),
                "consent copy versions are immutable");
    }

    @Test void an_invalid_registration_is_refused_with_the_reason() {
        var token = data.loginAsConfigAdmin(client);
        for (var bad : List.of(Map.of("purposes", List.of("spam")), Map.of("channel", "pigeon"),
                Map.of("version", "Bad Version"), Map.of("text", " "))) {
            var body = new HashMap<>(version);
            body.putAll(bad);
            assertEquals(400, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/consent-copy", body).bearerAuth(token))),
                    bad.toString());
        }
    }
}
