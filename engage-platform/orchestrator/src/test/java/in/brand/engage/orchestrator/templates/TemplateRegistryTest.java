package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.TemplateCooldowns;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The registry against Postgres: the templates table mirror and the policy hook. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class TemplateRegistryTest {

    @Inject TemplateRegistry registry;
    @Inject TemplateCooldowns cooldowns;
    @Inject Db db;

    @Test void startup_upserts_every_template_into_the_templates_table() {
        registry.sync();
        for (var t : registry.all()) {
            assertEquals(t.channel().dbName() + "/" + t.category().dbName(), row(t.key()).orElseThrow(), t.key());
        }
    }

    @Test void sync_corrects_channel_and_category_but_never_touches_an_operators_pause() {
        db.inTx(c -> Sql.update(c, """
                UPDATE templates SET category = 'marketing', status = 'paused'
                 WHERE key = 'push_back_in_stock_v1'"""));
        try {
            registry.sync();
            assertEquals("push/utility", row("push_back_in_stock_v1").orElseThrow());
            assertEquals("paused", status("push_back_in_stock_v1"));
        } finally {
            db.inTx(c -> Sql.update(c, "UPDATE templates SET status = 'active' WHERE key = 'push_back_in_stock_v1'"));
        }
    }

    @Test void sync_writes_nothing_when_nothing_changed() {
        registry.sync();
        assertEquals(0, registry.sync());
    }

    @Test void policy_gets_its_cooldowns_from_the_registry() {
        assertSame(registry, cooldowns, "TemplateCooldowns.None must give way to the registry");
        assertEquals(Optional.of(Duration.ofDays(1)), cooldowns.cooldown("push_price_drop_v1"));
        assertEquals(Optional.empty(), cooldowns.cooldown("push_back_in_stock_v1"), "the waitlist dedupes back-in-stock");
        assertEquals(Optional.empty(), cooldowns.cooldown("no_such_template"));
    }

    private Optional<String> row(String key) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT channel::text || '/' || category::text FROM templates WHERE key = ?", key);
                 var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
            }
        });
    }

    private String status(String key) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT status FROM templates WHERE key = ?", key);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }
}
