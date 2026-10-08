package in.brand.engage.admin.templates;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T04 template status: requested vs approved category, flagged. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class TemplatesTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;

    @Test void a_utility_template_meta_approved_as_marketing_is_flagged() {
        data.cleanAdminTables();
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO templates (key, channel, category, status) VALUES
                      ('tt_order_update', 'whatsapp', 'utility', 'active'),
                      ('tt_push_offer', 'push', 'marketing', 'active')
                    ON CONFLICT (key) DO UPDATE SET status = 'active'""");
            Sql.update(c, "DELETE FROM wa_templates WHERE key LIKE 'tt\\_%'");
            Sql.update(c, """
                    INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category, status, quality)
                    VALUES ('tt_order_update', 'en', 'order_update_v1', 'utility', 'marketing', 'APPROVED', 'GREEN'),
                           ('tt_order_update', 'hi', 'order_update_v1_hi', 'utility', 'utility', 'APPROVED', 'YELLOW')""");
            return null;
        });

        var all = (List<Map<String, Object>>) (List<?>) client.toBlocking().retrieve(
                HttpRequest.GET("/api/templates").bearerAuth(data.loginAsViewer(client)), Argument.listOf(Map.class));
        var wa = all.stream().filter(t -> "tt_order_update".equals(t.get("key"))).findFirst().orElseThrow();
        assertEquals(true, wa.get("categoryMismatch"));
        var languages = (List<Map<String, Object>>) wa.get("whatsapp");
        assertEquals(2, languages.size());
        assertEquals(true, languages.getFirst().get("categoryMismatch"), "en: utility approved as marketing");
        assertEquals(false, languages.get(1).get("categoryMismatch"));

        var push = all.stream().filter(t -> "tt_push_offer".equals(t.get("key"))).findFirst().orElseThrow();
        assertEquals(false, push.get("categoryMismatch"));
        assertEquals(List.of(), push.get("whatsapp"));
    }
}
