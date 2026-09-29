package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.TokenPrune;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.policy.BlockReason;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The router's three steps against Postgres, with a fake push provider. Noon IST unless a test says otherwise. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class MessageRouterTest {

    static final Map<String, String> VARS = Map.of("product", "Linen Kurta", "url", "https://w.example/p/linen-kurta");

    @Inject MessageRouter router;
    @Inject SendRepository sends;
    @Inject FakePushAdapter fcm;
    @Inject OrchestratorTestData data;
    @Inject TestClock clock;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T12:00:00");
        data.resetConfig();
        fcm.reset();
    }

    @Test void an_allowed_send_is_rendered_sent_and_marked_with_what_the_provider_accepted() {
        var s = data.subscriber();

        var result = assertInstanceOf(SendResult.Sent.class, router.send(command(s.id(), key())));

        var row = data.send(result.sendId());
        assertEquals("sent", row.get("status"));
        assertEquals(1L, row.get("delivered_count"));
        assertNotNull(row.get("config_snapshot_id"));
        var msg = fcm.sent.getFirst();
        assertEquals(result.sendId(), msg.sendId(), "the push sid is the sends row id, for the click beacon");
        assertEquals("Linen Kurta is still in stock. Take another look.", msg.body());
        assertEquals("test_browse", msg.kind());
    }

    @Test void the_persons_locale_picks_the_copy() {
        var s = data.subscriber();
        data.locale(s.id(), "hi-Latn");

        router.send(command(s.id(), key()));

        assertEquals("Abhi bhi soch rahe hain?", fcm.sent.getFirst().title());
    }

    @Test void a_duplicate_key_returns_duplicate_and_writes_no_second_row() {
        var s = data.subscriber();
        var key = key();
        var first = assertInstanceOf(SendResult.Sent.class, router.send(command(s.id(), key)));

        var second = assertInstanceOf(SendResult.Duplicate.class, router.send(command(s.id(), key)));

        assertEquals(first.sendId(), second.sendId());
        assertEquals("sent", second.status());
        assertEquals(1, fcm.sent.size());
        assertEquals(1L, data.count("SELECT count(*) FROM sends WHERE starts_with(idempotency_key, ?)", key));
    }

    @Test void a_block_is_persisted_with_its_reason_and_snapshot_and_nothing_is_sent() {
        var id = data.noConsent();

        var result = assertInstanceOf(SendResult.Blocked.class, router.send(command(id, key())));

        assertEquals(BlockReason.NO_MARKETING_CONSENT, result.reason());
        var row = data.send(result.sendId());
        assertEquals("blocked", row.get("status"));
        assertEquals("NO_MARKETING_CONSENT", row.get("reason"));
        assertNotNull(row.get("config_snapshot_id"));
        assertEquals(0, fcm.sent.size());
    }

    @Test void a_deferral_is_persisted_and_does_not_consume_the_idempotency_key() {
        var s = data.subscriber();
        var key = key();
        clock.setIst("2026-09-28T22:40:00");

        var first = assertInstanceOf(SendResult.Deferred.class, router.send(command(s.id(), key)));
        var second = assertInstanceOf(SendResult.Deferred.class, router.send(command(s.id(), key)));

        assertEquals(TestClock.ist("2026-09-29T09:00:00"), first.until());
        var row = data.send(first.sendId());
        assertEquals("deferred", row.get("status"));
        assertEquals("QUIET_HOURS", row.get("reason"));
        assertNotNull(row.get("config_snapshot_id"));
        assertEquals(key + "#defer:1", row.get("idempotency_key"));
        assertEquals(key + "#defer:2", data.send(second.sendId()).get("idempotency_key"));

        clock.setIst("2026-09-29T09:05:00");
        var sent = assertInstanceOf(SendResult.Sent.class, router.send(command(s.id(), key)),
                "the real key must still be free after two deferrals");
        assertEquals(key, data.send(sent.sendId()).get("idempotency_key"));
    }

    @Test void a_transient_provider_failure_is_failed_retriable_and_suppresses_nobody() {
        var s = data.subscriber();
        fcm.answer((m, a) -> { throw new ChannelException.Transient("no_token_accepted", "UNAVAILABLE", List.of(), null); });

        var result = assertInstanceOf(SendResult.Failed.class, router.send(command(s.id(), key())));

        assertTrue(result.retriable());
        assertEquals("failed", data.send(result.sendId()).get("status"));
        assertEquals("no_token_accepted", data.send(result.sendId()).get("failed_reason"));
        assertEquals(0L, suppressions(s.id()));
    }

    @Test void a_permanent_failure_that_blames_the_recipient_adds_a_suppression() {
        var s = data.subscriber();
        fcm.answer((m, a) -> { throw new ChannelException.Permanent("recipient_gone", null, true, List.of()); });

        var result = assertInstanceOf(SendResult.Failed.class, router.send(command(s.id(), key())));

        assertFalse(result.retriable());
        assertEquals(1L, data.count("SELECT count(*) FROM suppressions WHERE identity_id = ? AND reason = 'provider_recipient_gone'", s.id()));
    }

    @Test void a_permanent_failure_that_is_our_fault_suppresses_nobody() {
        var s = data.subscriber();
        fcm.answer((m, a) -> { throw new ChannelException.Permanent("payload_too_large", null, false, List.of()); });

        assertInstanceOf(SendResult.Failed.class, router.send(command(s.id(), key())));

        assertEquals(0L, suppressions(s.id()));
    }

    @Test void dead_tokens_reported_by_the_provider_are_pruned_after_the_send() {
        var s = data.subscriber();
        fcm.acceptPruning(List.of(new TokenPrune(s.deviceId(), "unregistered")));

        router.send(command(s.id(), key()));

        assertEquals(1L, data.count("SELECT count(*) FROM devices WHERE id = ? AND NOT active AND deactivated_reason = 'unregistered'", s.deviceId()));
    }

    @Test void dead_tokens_are_pruned_even_when_nothing_was_accepted() {
        var s = data.subscriber();
        fcm.answer((m, a) -> { throw new ChannelException.Permanent("all_tokens_dead", null, false,
                List.of(new TokenPrune(s.deviceId(), "unregistered"))); });

        router.send(command(s.id(), key()));

        assertEquals(1L, data.count("SELECT count(*) FROM devices WHERE id = ? AND NOT active", s.deviceId()));
    }

    @Test void a_missing_template_variable_fails_the_send_without_calling_the_provider() {
        var s = data.subscriber();

        var result = assertInstanceOf(SendResult.Failed.class,
                router.send(command(s.id(), key(), Map.of("url", "https://w.example/p"))));

        assertEquals("render_failed", result.code());
        assertEquals(0, fcm.sent.size());
        assertEquals(0L, suppressions(s.id()));
    }

    @Test void the_sweeper_fails_queued_rows_older_than_ten_minutes_and_leaves_newer_ones() {
        var s = data.subscriber();
        long lost = data.queuedRow(s.id(), clock.instant().minus(Duration.ofMinutes(11)));
        long inFlight = data.queuedRow(s.id(), clock.instant().minus(Duration.ofMinutes(5)));

        sends.sweepLostInFlight();

        assertEquals("failed", data.send(lost).get("status"));
        assertEquals("lost_in_flight", data.send(lost).get("failed_reason"));
        assertEquals("queued", data.send(inFlight).get("status"), "it may still be at the provider");
    }

    private long suppressions(UUID id) {
        return data.count("SELECT count(*) FROM suppressions WHERE identity_id = ?", id);
    }

    static SendCommand command(UUID identityId, String key) {
        return command(identityId, key, VARS);
    }

    static SendCommand command(UUID identityId, String key, Map<String, String> vars) {
        return new SendCommand(identityId, TestCascades.BROWSE, null, 0, Channel.PUSH, "push_browse_abandon_v1",
                vars, key, Duration.ofHours(24), false, false);
    }

    static String key() {
        return "test:" + UUID.randomUUID();
    }
}
