package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.UnsubscribeToken;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.ConfigResolver;
import in.brand.engage.worker.EventDispatcher;
import in.brand.engage.worker.TestClock;
import in.brand.engage.worker.WorkerTestBeans;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T01: a journey's email step, rendered with UTMs and a one-click unsubscribe link. */
@MicronautTest(transactional = false)
@Property(name = "PUBLIC_BASE_URL", value = "https://engage-test.example")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class EmailStepTest {

    @Inject EventDispatcher dispatcher;
    @Inject DefaultOrchestrator orchestrator;
    @Inject ConfigResolver config;
    @Inject TestClock clock;
    @Inject Db db;
    @Inject WorkerTestBeans.FakeEmail email;
    @Inject WorkerTestBeans.FakePush push;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        email.all.clear();
        push.all.clear();
        db.inTx(c -> {
            try (var st = c.createStatement()) {
                st.execute("UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL");
                st.execute("UPDATE cascade_runs SET status = 'cancelled', outcome = 'test_reset' WHERE status IN ('active','waiting')");
                st.execute("TRUNCATE config_versions CASCADE");
            }
            for (var key : new String[] {"holdout.global_pct", "holdout.journey_pct"}) {
                Sql.update(c, """
                        INSERT INTO config_versions (key, selector, value, changed_by, reason)
                        VALUES (?, '*', '0', (SELECT id FROM operators ORDER BY created_at LIMIT 1), 'test')""", key);
            }
            return null;
        });
        config.invalidate();
    }

    @Test void a_customer_only_email_can_reach_gets_the_cart_email_with_utms_and_their_unsubscribe_link() {
        var who = UUID.randomUUID();
        var cart = "test-cart-" + UUID.randomUUID();
        db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", who);
            Sql.update(c, "INSERT INTO identity_keys (identity_id, kind, value, verified) VALUES (?, 'email', ?, true)",
                    who, who + "@example.com");
            Sql.update(c, "INSERT INTO profiles (identity_id, attrs) VALUES (?, '{\"first_name\":\"Priya\"}')", who);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, occurred_at)
                    VALUES (?, 'email', 'marketing', 'granted', 'test', now() - interval '30 days')""", who);
            Sql.update(c, """
                    INSERT INTO carts (cart_token, identity_id, item_count, total_paise, lines)
                    VALUES (?, ?, 1, 1170000, '[{"variant_id":"2","title":"Kundan Jhumka Earrings","qty":1,"price_paise":1170000}]'::jsonb)""",
                    cart, who);
            return Sql.update(c, "INSERT INTO events (identity_id, name, props, source, occurred_at) VALUES (?, 'cart_updated', ?::jsonb, 'shopify', ?)",
                    who, "{\"cart_token\":\"" + cart + "\",\"item_count\":1}", clock.instant().atOffset(ZoneOffset.UTC));
        });
        dispatcher.dispatchOnce();
        clock.setIst("2026-09-28T12:46:00");
        orchestrator.tick(200);
        // Neither WhatsApp nor push can reach this person, and skipped steps do not wait:
        // the email, the last step, is the one that goes, at 45 minutes.
        assertTrue(push.all.isEmpty());

        assertEquals(1, email.all.size());
        var m = email.all.getFirst().email();
        assertEquals("Still thinking about Kundan Jhumka Earrings?", m.subject());
        assertTrue(m.marketing());
        assertTrue(m.html().contains("utm_medium=email"), "UTMs on the button link");
        assertTrue(m.unsubscribeUrl().startsWith("https://engage-test.example/u/"), m.unsubscribeUrl());
        var token = m.unsubscribeUrl().substring("https://engage-test.example/u/".length());
        assertEquals(who, UnsubscribeToken.fromAppSecret(System.getenv("SHOPIFY_API_SECRET")).parse(token).orElseThrow());
    }
}
