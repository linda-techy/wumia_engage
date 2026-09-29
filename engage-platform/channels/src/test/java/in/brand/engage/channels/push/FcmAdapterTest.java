package in.brand.engage.channels.push;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.TokenPrune;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** FcmAdapter against a fake FCM: no network, no database. */
class FcmAdapterTest {

    static final String INVALID_TOKEN_MSG = "The registration token is not a valid FCM registration token";

    @Test void mixed_per_token_results_prune_exactly_the_dead_tokens_with_the_right_reasons() throws Exception {
        var fcm = new FakeFcm(token -> switch (token) {
            case "t1" -> FcmClient.TokenResult.ok();
            case "t2" -> FcmClient.TokenResult.error("UNREGISTERED", "Requested entity was not found.");
            case "t3" -> FcmClient.TokenResult.error("SENDER_ID_MISMATCH", "SenderId mismatch");
            case "t4" -> FcmClient.TokenResult.error("INVALID_ARGUMENT", INVALID_TOKEN_MSG);
            case "t5" -> FcmClient.TokenResult.error("INVALID_ARGUMENT", "Invalid value at 'message.data'");
            case "t6" -> FcmClient.TokenResult.error("UNAVAILABLE", "try again");
            default -> throw new AssertionError(token);
        });

        var result = new FcmAdapter(fcm).send(message("Linen Kurta"), targets(6));

        assertEquals(1, result.delivered());
        assertNull(result.providerId(), "FCM multicast has no single message id");
        assertEquals(List.of(new TokenPrune(2, "unregistered"), new TokenPrune(3, "sender_mismatch"),
                new TokenPrune(4, "invalid_token")), result.prunes(),
                "a payload INVALID_ARGUMENT (t5) and a transient error (t6) must not prune");
    }

    @Test void a_4001_byte_payload_is_rejected_before_calling_fcm_and_4000_goes_through() throws Exception {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok());
        var adapter = new FcmAdapter(fcm);
        int base = FcmAdapter.dataBytes(message("").pushData());

        var e = assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(message("x".repeat(4_001 - base)), targets(1)));
        assertEquals("payload_too_large", e.code());
        assertFalse(e.suppress(), "an oversized payload is our bug, never the customer's");
        assertEquals(0, fcm.calls.size());

        adapter.send(message("x".repeat(4_000 - base)), targets(1));
        assertEquals(1, fcm.calls.size());
    }

    @Test void payload_size_counts_utf8_bytes_not_characters() {
        assertEquals(4 + 3, FcmAdapter.dataBytes(Map.of("body", "₹")));   // ₹ is 3 bytes
    }

    @Test void twelve_hundred_tokens_go_out_in_three_calls_of_at_most_500() throws Exception {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok());

        var result = new FcmAdapter(fcm).send(message("b"), targets(1_200));

        assertEquals(List.of(500, 500, 200), fcm.calls.stream().map(b -> b.tokens().size()).toList());
        assertEquals(1_200, result.delivered());
    }

    @Test void zero_accepted_because_every_token_is_dead_is_a_permanent_failure_that_still_prunes() {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.error("UNREGISTERED", "gone"));

        var e = assertThrows(ChannelException.Permanent.class, () -> new FcmAdapter(fcm).send(message("b"), targets(2)));

        assertEquals("all_tokens_dead", e.code());
        assertFalse(e.suppress(), "a new device may subscribe tomorrow");
        assertEquals(List.of(new TokenPrune(1, "unregistered"), new TokenPrune(2, "unregistered")), e.prunes());
    }

    @Test void zero_accepted_with_a_transient_error_is_retriable() {
        var fcm = new FakeFcm(t -> t.equals("t1")
                ? FcmClient.TokenResult.error("UNREGISTERED", "gone")
                : FcmClient.TokenResult.error("QUOTA_EXCEEDED", "slow down"));

        var e = assertThrows(ChannelException.Transient.class, () -> new FcmAdapter(fcm).send(message("b"), targets(2)));

        assertEquals("no_token_accepted", e.code());
        assertEquals(List.of(new TokenPrune(1, "unregistered")), e.prunes());
    }

    @Test void a_failed_call_with_nothing_accepted_is_transient() {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok()).failingCall(0);

        var e = assertThrows(ChannelException.Transient.class, () -> new FcmAdapter(fcm).send(message("b"), targets(3)));
        assertEquals("fcm_call_failed", e.code());
    }

    @Test void a_call_failing_after_an_earlier_chunk_was_accepted_reports_what_went_out() throws Exception {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok()).failingCall(1);

        var result = new FcmAdapter(fcm).send(message("b"), targets(700));

        assertEquals(500, result.delivered(), "the first 500 were accepted and must not be reported as a failure");
    }

    @Test void no_targets_is_a_caller_bug_and_calls_nothing() {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok());
        var e = assertThrows(ChannelException.Permanent.class,
                () -> new FcmAdapter(fcm).send(message("b"), new Addresses(List.of(), null, null)));
        assertEquals("no_live_tokens", e.code());
        assertEquals(0, fcm.calls.size());
    }

    @Test void the_batch_is_data_only_with_ttl_urgency_and_a_valid_topic() throws Exception {
        var fcm = new FakeFcm(t -> FcmClient.TokenResult.ok());
        var msg = new RenderedMessage(Channel.PUSH, "push_back_in_stock_v1", 42, "back_in_stock",
                "Size M is back: Linen Kurta", "Only a few pieces in this restock.", "https://w.example/p",
                null, "restock:4471", Duration.ofHours(1), true, null);

        new FcmAdapter(fcm).send(msg, targets(1));

        var batch = fcm.calls.getFirst();
        assertEquals(Map.of("sid", "42", "kind", "back_in_stock", "title", "Size M is back: Linen Kurta",
                "body", "Only a few pieces in this restock.", "url", "https://w.example/p", "tag", "restock:4471"),
                batch.data(), "no image key when there is no image");
        assertEquals(Duration.ofHours(1), batch.ttl());
        assertTrue(batch.highUrgency());
        assertTrue(batch.topic().matches("[A-Za-z0-9_-]{32}"), "':' is not allowed in a Web Push Topic: " + batch.topic());
        assertEquals(batch.topic(), FcmAdapter.topic("restock:4471"), "stable, so a newer restock replaces the older");
    }

    @Test void a_topic_safe_tag_is_used_as_is() {
        assertEquals("back_in_stock", FcmAdapter.topic("back_in_stock"));
        assertNull(FcmAdapter.topic(null));
    }

    @Test void the_real_client_builds_a_500_token_message_without_touching_the_network() {
        var tokens = LongStream.rangeClosed(1, 500).mapToObj(i -> "t" + i).toList();
        assertNotNull(FirebaseFcmClient.build(new FcmClient.Batch(tokens, Map.of("sid", "1"),
                Duration.ofHours(12), false, "cart_recovery")));
    }

    @Test void a_missing_service_account_file_fails_with_the_variable_named() {
        var blank = assertThrows(IllegalStateException.class, () -> FcmClientFactory.app(""));
        assertTrue(blank.getMessage().startsWith("FIREBASE_SERVICE_ACCOUNT_FILE is not set"), blank.getMessage());
        var missing = assertThrows(IllegalStateException.class, () -> FcmClientFactory.app("no/such/file.json"));
        assertTrue(missing.getMessage().contains("is not a readable file"), missing.getMessage());
    }

    static RenderedMessage message(String body) {
        return new RenderedMessage(Channel.PUSH, "push_test_v1", 7, "cart_recovery", "Title", body,
                "https://w.example/cart", null, null, Duration.ofHours(12), false, null);
    }

    /** Devices 1..n with tokens t1..tn. */
    static Addresses targets(int n) {
        return new Addresses(LongStream.rangeClosed(1, n).mapToObj(i -> new Addresses.PushTarget(i, "t" + i)).toList(),
                null, null);
    }
}
