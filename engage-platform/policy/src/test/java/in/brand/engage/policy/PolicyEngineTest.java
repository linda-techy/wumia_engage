package in.brand.engage.policy;

import static in.brand.engage.core.messaging.Category.MARKETING;
import static in.brand.engage.core.messaging.Category.UTILITY;
import static in.brand.engage.core.messaging.Channel.PUSH;
import static in.brand.engage.core.messaging.Channel.WHATSAPP;
import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The policy rules, one test per rule, against Postgres. The clock is pinned
 * to noon IST unless a test says otherwise, so quiet hours never leak in.
 */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PolicyEngineTest {

    static final String PUSH_MKT = "test_push_marketing";
    static final String PUSH_UTIL = "test_push_utility";
    static final String PUSH_PAUSED = "test_push_paused";
    static final String WA_UTIL = "test_wa_utility";
    static final String WA_MKT = "test_wa_marketing";

    @Inject PolicyEngine policy;
    @Inject ConfigResolver config;
    @Inject PolicyTestData data;
    @Inject TestClock clock;
    @Inject Db db;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        data.resetConfig();
        // The 5% global default would put a random 1 in 20 marketing test identities in control.
        data.setConfig("holdout.global_pct", "*", "0");
        config.invalidate();
        data.template(PUSH_MKT, PUSH, MARKETING, "active");
        data.template(PUSH_UTIL, PUSH, UTILITY, "active");
        data.template(PUSH_PAUSED, PUSH, MARKETING, "paused");
        data.template(WA_UTIL, WHATSAPP, UTILITY, "active");
        data.template(WA_MKT, WHATSAPP, MARKETING, "active");
    }

    /* ----------------------------- kill switches ----------------------------- */

    @Test void channel_halted_blocks_every_send_on_it_utility_included() {
        var id = pushSubscriber();
        data.setConfig("halt.channel", "push", "true");

        assertBlocked(BlockReason.CHANNEL_HALTED, decide(id, PUSH, PUSH_MKT));
        assertBlocked(BlockReason.CHANNEL_HALTED, decide(id, PUSH, PUSH_UTIL));
    }

    @Test void marketing_halted_blocks_marketing_and_lets_utility_through() {
        var id = pushSubscriber();
        data.setConfig("halt.marketing", "*", "true");

        assertBlocked(BlockReason.MARKETING_HALTED, decide(id, PUSH, PUSH_MKT));
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_UTIL));
    }

    @Test void halt_written_in_one_connection_is_seen_by_the_next_decision_in_another_with_no_wait() {
        var id = pushSubscriber();
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_MKT));   // config cache now warm

        data.setConfig("halt.channel", "push", "true");                      // its own connection and commit

        assertBlocked(BlockReason.CHANNEL_HALTED, decide(id, PUSH, PUSH_MKT));
    }

    @Test void disabled_journey_is_blocked() {
        var id = pushSubscriber();
        data.setConfig("journey.enabled", "cart_recovery", "false");
        config.invalidate();

        assertBlocked(BlockReason.JOURNEY_DISABLED, policy.decide(
                DecisionRequest.of(id, PUSH, PUSH_MKT).forJourney("cart_recovery")));
        assertInstanceOf(Decision.Allow.class, policy.decide(
                DecisionRequest.of(id, PUSH, PUSH_MKT).forJourney("browse_abandon")));
    }

    /* ------------------------------- templates ------------------------------- */

    @Test void unknown_template_is_blocked() {
        assertBlocked(BlockReason.TEMPLATE_UNKNOWN, decide(pushSubscriber(), PUSH, "no_such_template"));
    }

    @Test void paused_template_is_blocked() {
        assertBlocked(BlockReason.TEMPLATE_PAUSED, decide(pushSubscriber(), PUSH, PUSH_PAUSED));
    }

    @Test void template_for_another_channel_is_blocked() {
        assertBlocked(BlockReason.CHANNEL_MISMATCH, decide(pushSubscriber(), PUSH, WA_UTIL));
    }

    /* ------------------------------ reachability ------------------------------ */

    @Test void ios_web_only_identity_is_unreachable_on_push() {
        var id = data.newIdentity();
        data.device(id, "IOS_WEB", clock.instant());
        data.grant(id, PUSH, "marketing", daysAgo(1));

        assertBlocked(BlockReason.UNREACHABLE, decide(id, PUSH, PUSH_MKT));
    }

    @Test void stale_token_is_a_target_only_when_the_intent_allows_stale_tokens() {
        var id = data.newIdentity();
        data.device(id, "WEB", daysAgo(45));
        data.grant(id, PUSH, "marketing", daysAgo(50));

        assertBlocked(BlockReason.UNREACHABLE, decide(id, PUSH, PUSH_MKT));
        var allow = assertInstanceOf(Decision.Allow.class,
                policy.decide(DecisionRequest.of(id, PUSH, PUSH_UTIL).allowingStaleDevices()));
        assertEquals(1, allow.addresses().push().size());
    }

    @Test void suppressed_identity_is_blocked_with_the_reason() {
        var id = pushSubscriber();
        db.inTx(c -> in.brand.engage.persistence.Sql.update(c,
                "INSERT INTO suppressions (identity_id, channel, reason) VALUES (?, 'push', 'test_bounce')", id));

        var block = assertBlocked(BlockReason.SUPPRESSED, decide(id, PUSH, PUSH_MKT));
        assertEquals("test_bounce", block.detail().get("reason"));
    }

    /* -------------------------------- consent -------------------------------- */

    @Test void push_without_a_push_marketing_grant_is_blocked_even_for_utility() {
        var id = data.newIdentity();
        data.device(id, "WEB", clock.instant());

        assertBlocked(BlockReason.NO_MARKETING_CONSENT, decide(id, PUSH, PUSH_MKT));
        assertBlocked(BlockReason.NO_MARKETING_CONSENT, decide(id, PUSH, PUSH_UTIL));
    }

    @Test void whatsapp_utility_without_any_whatsapp_grant_is_blocked() {
        var id = waContact();
        data.grant(id, PUSH, "marketing", daysAgo(1));   // a grant on another channel does not count

        assertBlocked(BlockReason.NO_WHATSAPP_OPT_IN, decide(id, WHATSAPP, WA_UTIL));
    }

    @Test void whatsapp_utility_with_a_transactional_only_grant_is_allowed() {
        var id = waContact();
        data.grant(id, WHATSAPP, "transactional", daysAgo(1));

        assertInstanceOf(Decision.Allow.class, decide(id, WHATSAPP, WA_UTIL));
    }

    @Test void whatsapp_marketing_with_a_transactional_only_grant_is_blocked() {
        var id = waContact();
        data.grant(id, WHATSAPP, "transactional", daysAgo(1));

        assertBlocked(BlockReason.NO_MARKETING_CONSENT, decide(id, WHATSAPP, WA_MKT));
    }

    @Test void withdrawn_after_granted_is_blocked_because_the_latest_statement_wins() {
        var id = data.newIdentity();
        data.device(id, "WEB", clock.instant());
        // Inserted out of order: the withdrawal is written first but happened later.
        data.consent(id, PUSH, "marketing", "withdrawn", daysAgo(1));
        data.consent(id, PUSH, "marketing", "granted", daysAgo(2));

        assertBlocked(BlockReason.NO_MARKETING_CONSENT, decide(id, PUSH, PUSH_MKT));
    }

    /* ------------------------------ quiet hours ------------------------------ */

    @Test void marketing_at_2240_ist_defers_to_0900_ist_the_next_day() {
        var id = pushSubscriber();
        clock.setIst("2026-09-28T22:40:00");

        var defer = assertInstanceOf(Decision.Defer.class, decide(id, PUSH, PUSH_MKT));
        assertEquals(BlockReason.QUIET_HOURS, defer.reason());
        assertEquals(ist("2026-09-29T09:00:00"), defer.until());
    }

    @Test void utility_is_exempt_from_quiet_hours() {
        var id = pushSubscriber();
        clock.setIst("2026-09-28T22:40:00");

        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_UTIL));
    }

    @Test void the_boundary_minute_2100_is_inside_quiet_hours_and_0900_is_outside() {
        var id = pushSubscriber();

        clock.setIst("2026-09-28T21:00:00");
        assertInstanceOf(Decision.Defer.class, decide(id, PUSH, PUSH_MKT));

        clock.setIst("2026-09-29T08:59:59");
        var defer = assertInstanceOf(Decision.Defer.class, decide(id, PUSH, PUSH_MKT));
        assertEquals(ist("2026-09-29T09:00:00"), defer.until(), "after midnight, the same day's 09:00");

        clock.setIst("2026-09-29T09:00:00");
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_MKT));
    }

    /* ---------------------------- frequency caps ---------------------------- */

    @Test void frequency_cap_counts_across_journeys_not_within_one() {
        var id = pushSubscriber();                                 // cap.push.marketing.1d default = 3
        data.send(id, PUSH, MARKETING, "cart_recovery", "sent", hoursAgo(2));
        data.send(id, PUSH, MARKETING, "browse_abandon", "clicked", hoursAgo(3));
        data.send(id, PUSH, MARKETING, "price_drop", "failed", hoursAgo(4));

        var block = assertBlocked(BlockReason.FREQUENCY_CAP, decide(id, PUSH, PUSH_MKT));
        assertEquals(3L, block.detail().get("used"));
    }

    @Test void frequency_cap_excludes_blocked_and_deferred_rows_and_older_sends() {
        var id = pushSubscriber();
        data.send(id, PUSH, MARKETING, "cart_recovery", "sent", hoursAgo(2));
        data.send(id, PUSH, MARKETING, "cart_recovery", "sent", hoursAgo(3));
        data.send(id, PUSH, MARKETING, "price_drop", "blocked", hoursAgo(1));
        data.send(id, PUSH, MARKETING, "price_drop", "deferred", hoursAgo(1));
        data.send(id, PUSH, MARKETING, "price_drop", "sent", hoursAgo(25));

        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_MKT));
    }

    /* -------------------------------- budget -------------------------------- */

    @Test void budget_exhausted_defers_to_the_next_ist_day_and_does_not_drop() {
        var id = waMarketingContact();                    // budget 200000, rate 86 by default
        data.spend(today(), WHATSAPP, MARKETING, 199_915);   // 199915 + 86 = 200001 > budget

        var defer = assertInstanceOf(Decision.Defer.class, decide(id, WHATSAPP, WA_MKT));
        assertEquals(BlockReason.DAILY_BUDGET_EXHAUSTED, defer.reason());
        assertEquals(ist("2026-09-29T00:00:00"), defer.until());
    }

    @Test void budget_allows_the_send_that_lands_exactly_on_it() {
        var id = waMarketingContact();
        data.spend(today(), WHATSAPP, MARKETING, 199_914);   // 199914 + 86 = 200000

        var allow = assertInstanceOf(Decision.Allow.class, decide(id, WHATSAPP, WA_MKT));
        assertEquals(86L, allow.unitCostPaise());
    }

    @Test void budget_zero_means_unlimited() {
        var id = waMarketingContact();
        data.setConfig("budget.whatsapp.marketing.daily_paise", "*", "0");
        config.invalidate();
        data.spend(today(), WHATSAPP, MARKETING, 50_000_000);

        assertInstanceOf(Decision.Allow.class, decide(id, WHATSAPP, WA_MKT));
    }

    /* -------------------------------- config -------------------------------- */

    @Test void a_key_with_no_version_resolves_to_its_default_and_a_version_overrides_it() {
        assertEquals(3L, config.snapshot().longValue("cap.push.marketing.1d", "*"));

        data.setConfig("cap.push.marketing.1d", "*", "5");
        config.invalidate();
        assertEquals(5L, config.snapshot().longValue("cap.push.marketing.1d", "*"));

        data.setConfig("journey.enabled", "cart_recovery", "false");
        config.invalidate();
        assertFalse(config.snapshot().boolValue("journey.enabled", "cart_recovery"), "exact selector wins");
        assertTrue(config.snapshot().boolValue("journey.enabled", "browse_abandon"), "else '*', else default");
    }

    @Test void a_key_with_neither_a_version_nor_a_default_fails_resolver_startup() {
        data.keyWithoutDefault("test.no_default");
        var resolver = new ConfigResolver(db, List.of("cap.push.marketing.1d", "test.no_default"));

        var e = assertThrows(IllegalStateException.class, resolver::verifyRequiredKeys);
        assertTrue(e.getMessage().contains("test.no_default"), e.getMessage());
        assertFalse(e.getMessage().contains("cap.push.marketing.1d"), e.getMessage());
    }

    @Test void a_config_change_drops_the_cache_through_listen_notify_within_a_second() throws Exception {
        waitUntil(config::listening, Duration.ofSeconds(10), "config_changed listener never connected");
        assertEquals(3L, config.snapshot().longValue("cap.push.marketing.1d", "*"));

        data.setConfig("cap.push.marketing.1d", "*", "7");      // no invalidate(): the trigger must do it

        waitUntil(() -> config.snapshot().longValue("cap.push.marketing.1d", "*") == 7L,
                Duration.ofSeconds(1), "cache still stale a second after the change");
    }

    /* ------------------------------- holdouts ------------------------------- */

    @Test void holdout_control_is_blocked() {
        var id = pushSubscriber();
        data.setConfig("holdout.journey_pct", "cart_recovery", "100");
        config.invalidate();

        var block = assertBlocked(BlockReason.HOLDOUT_CONTROL,
                policy.decide(DecisionRequest.of(id, PUSH, PUSH_MKT).forJourney("cart_recovery")));
        assertEquals("cart_recovery", block.detail().get("experiment"));
        assertEquals("control", data.holdoutBucket(id, "cart_recovery"));
    }

    @Test void holdout_bucket_is_stable_across_two_calls_and_a_pct_change() {
        UUID id;
        int slot;
        do {
            id = pushSubscriber();
            slot = Holdouts.slot("cart_recovery", id);
        } while (slot == 0);
        var req = DecisionRequest.of(id, PUSH, PUSH_MKT).forJourney("cart_recovery");

        // Just wide enough to put this identity in control.
        data.setConfig("holdout.journey_pct", "cart_recovery", BigDecimal.valueOf(slot + 1, 2).toPlainString());
        config.invalidate();
        assertBlocked(BlockReason.HOLDOUT_CONTROL, policy.decide(req));
        assertBlocked(BlockReason.HOLDOUT_CONTROL, policy.decide(req));

        // Narrowed so that a fresh assignment would now be treatment.
        var narrower = BigDecimal.valueOf(slot, 2);
        assertEquals(Holdouts.Bucket.TREATMENT, Holdouts.assign("cart_recovery", id, narrower));
        data.setConfig("holdout.journey_pct", "cart_recovery", narrower.toPlainString());
        config.invalidate();
        assertBlocked(BlockReason.HOLDOUT_CONTROL, policy.decide(req));
    }

    @Test void global_holdout_blocks_marketing_only() {
        var id = pushSubscriber();
        data.setConfig("holdout.global_pct", "*", "100");
        config.invalidate();

        assertBlocked(BlockReason.HOLDOUT_GLOBAL, decide(id, PUSH, PUSH_MKT));
        assertInstanceOf(Decision.Allow.class, decide(id, PUSH, PUSH_UTIL));
    }

    /* ------------------------------ provenance ------------------------------ */

    @Test void every_decision_carries_the_config_snapshot_id() {
        long snapshotId = config.snapshot().id();
        assertTrue(snapshotId > 0);

        var allow = decide(pushSubscriber(), PUSH, PUSH_MKT);
        var block = decide(data.newIdentity(), PUSH, PUSH_MKT);
        clock.setIst("2026-09-28T23:00:00");
        var defer = decide(pushSubscriber(), PUSH, PUSH_MKT);

        assertInstanceOf(Decision.Allow.class, allow);
        assertInstanceOf(Decision.Block.class, block);
        assertInstanceOf(Decision.Defer.class, defer);
        for (var d : List.of(allow, block, defer)) assertEquals(snapshotId, d.configSnapshotId(), d.toString());
    }

    @Test void a_snapshot_is_reused_while_config_is_unchanged_and_replaced_when_it_changes() {
        long first = config.snapshot().id();
        config.invalidate();
        assertEquals(first, config.snapshot().id(), "same config, same fingerprint, same row");

        data.setConfig("cap.push.marketing.1d", "*", "4");
        config.invalidate();
        assertNotEquals(first, config.snapshot().id());
    }

    /* -------------------------------- helpers -------------------------------- */

    private Decision decide(UUID id, in.brand.engage.core.messaging.Channel channel, String template) {
        return policy.decide(DecisionRequest.of(id, channel, template));
    }

    private UUID pushSubscriber() {
        var id = data.newIdentity();
        data.device(id, "WEB", clock.instant());
        data.grant(id, PUSH, "marketing", daysAgo(1));
        return id;
    }

    private UUID waContact() {
        var id = data.newIdentity();
        data.phone(id, "91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L)));
        return id;
    }

    private UUID waMarketingContact() {
        var id = waContact();
        data.grant(id, WHATSAPP, "transactional", daysAgo(1));
        data.grant(id, WHATSAPP, "marketing", daysAgo(1));
        return id;
    }

    private static Decision.Block assertBlocked(BlockReason reason, Decision decision) {
        var block = assertInstanceOf(Decision.Block.class, decision, () -> "expected " + reason + ", got " + decision);
        assertEquals(reason, block.reason());
        return block;
    }

    private Instant daysAgo(int days) {
        return clock.instant().minus(Duration.ofDays(days));
    }

    private Instant hoursAgo(int hours) {
        return clock.instant().minus(Duration.ofHours(hours));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TestClock.IST);
    }

    private static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(TestClock.IST).toInstant();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail(message);
            Thread.sleep(20);
        }
    }
}
