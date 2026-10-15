package in.brand.engage.policy;

import static in.brand.engage.core.messaging.Category.MARKETING;
import static in.brand.engage.core.messaging.Category.UTILITY;
import static in.brand.engage.core.messaging.Channel.PUSH;
import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** P5-T01: journeys collide by priority (phase-5 §5). 1 transactional · 2 high · 3 mid · 4 low intent. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CollisionTest {

    static final String PUSH_MKT = "test_push_marketing";
    static final String PUSH_UTIL = "test_push_utility";

    @Inject PolicyEngine policy;
    @Inject ConfigResolver config;
    @Inject PolicyTestData data;
    @Inject TestClock clock;
    @Inject Db db;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        data.resetConfig();
        data.setConfig("holdout.global_pct", "*", "0");
        data.setConfig("holdout.journey_pct", "*", "0");    // V32 defaults it to 10%
        config.invalidate();
        data.template(PUSH_MKT, PUSH, MARKETING, "active");
        data.template(PUSH_UTIL, PUSH, UTILITY, "active");
    }

    /** sender priority × other live run's priority → blocked? */
    @ParameterizedTest(name = "sender {0}, live {1} → blocked {2}")
    @CsvSource({
            "1,1,false", "1,2,false", "1,3,false", "1,4,false",   // transactional is never blocked
            "2,1,false", "2,2,false", "2,3,false", "2,4,false",   // nothing outranks high intent but transactional, which never blocks
            "3,1,false", "3,2,true",  "3,3,false", "3,4,false",
            "4,1,false", "4,2,true",  "4,3,true",  "4,4,false"})
    void the_collision_table(int sender, int other, boolean blocked) {
        var id = subscriber();
        run(id, "other_journey", other, "active", clock.instant());
        long me = run(id, "my_journey", sender, "active", clock.instant());

        var decision = decide(id, sender == 1 ? PUSH_UTIL : PUSH_MKT, me);

        if (blocked) {
            var block = assertInstanceOf(Decision.Block.class, decision);
            assertEquals(BlockReason.HIGHER_PRIORITY_ACTIVE, block.reason());
        } else {
            assertInstanceOf(Decision.Allow.class, decision);
        }
    }

    @Test void a_more_important_journey_that_just_ended_still_holds_for_12_hours() {
        var id = subscriber();
        run(id, "checkout_abandon", 2, "succeeded", clock.instant().minus(Duration.ofHours(11)));
        long cart = run(id, "cart_recovery", 3, "active", clock.instant());
        assertInstanceOf(Decision.Block.class, decide(id, PUSH_MKT, cart));
    }

    @Test void the_quiet_time_expires() {
        var id = subscriber();
        run(id, "checkout_abandon", 2, "succeeded", clock.instant().minus(Duration.ofHours(13)));
        long cart = run(id, "cart_recovery", 3, "active", clock.instant());
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH_MKT, cart));
    }

    @Test void the_quiet_time_is_config() {
        data.setConfig("journey.collision.suppress_hours", "*", "0");
        config.invalidate();
        var id = subscriber();
        run(id, "checkout_abandon", 2, "succeeded", clock.instant().minus(Duration.ofMinutes(1)));
        long cart = run(id, "cart_recovery", 3, "active", clock.instant());
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH_MKT, cart));
    }

    @Test void someone_elses_journey_does_not_count() {
        var other = subscriber();
        run(other, "checkout_abandon", 2, "active", clock.instant());
        var id = subscriber();
        long cart = run(id, "cart_recovery", 3, "active", clock.instant());
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH_MKT, cart));
    }

    @Test void a_send_outside_a_cascade_is_not_checked() {
        var id = subscriber();
        run(id, "checkout_abandon", 2, "active", clock.instant());
        assertInstanceOf(Decision.Allow.class, policy.decide(DecisionRequest.of(id, PUSH, PUSH_MKT)));
    }

    /* -------------------------------- fixtures -------------------------------- */

    private Decision decide(UUID id, String template, long runId) {
        return policy.decide(DecisionRequest.of(id, PUSH, template).forJourney("my_journey").inRun(runId));
    }

    private UUID subscriber() {
        var id = data.newIdentity();
        data.device(id, "WEB", clock.instant());
        data.grant(id, PUSH, "marketing", clock.instant().minus(Duration.ofDays(1)));
        return id;
    }

    private long run(UUID id, String intent, int priority, String status, Instant updatedAt) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    intent, id, "subj:" + UUID.randomUUID(), priority, status, updatedAt.atOffset(ZoneOffset.UTC));
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
