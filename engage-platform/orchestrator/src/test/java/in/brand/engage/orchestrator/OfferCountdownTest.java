package in.brand.engage.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V44: the time-left wording and the last-chance reminder schedule. No database. */
class OfferCountdownTest {

    @Test void time_left_reads_short_and_never_says_zero() {
        assertEquals("3 days", OfferCountdown.timeLeft(Duration.ofDays(3)));
        assertEquals("1 day 5h", OfferCountdown.timeLeft(Duration.ofHours(29)));
        assertEquals("1 day", OfferCountdown.timeLeft(Duration.ofHours(24)));
        assertEquals("5h 12m", OfferCountdown.timeLeft(Duration.ofMinutes(312)));
        assertEquals("3h", OfferCountdown.timeLeft(Duration.ofHours(3)));
        assertEquals("45 min", OfferCountdown.timeLeft(Duration.ofMinutes(45)));
        assertEquals("1 min", OfferCountdown.timeLeft(Duration.ofSeconds(10)));
        assertEquals("Dhanteras offer ends in 2h", OfferCountdown.fill("Dhanteras offer ends in {time_left}", "2h"));
    }

    @Test void the_end_comes_from_the_campaign_vars() {
        assertEquals(Instant.parse("2026-11-06T18:29:00Z"), OfferCountdown.endsAt(Map.of("ends_at", "2026-11-06T18:29:00Z")).orElseThrow());
        assertTrue(OfferCountdown.endsAt(Map.of()).isEmpty());
        assertTrue(OfferCountdown.endsAt(Map.of("ends_at", "tomorrow")).isEmpty());
    }

    static List<Duration> waits(CascadeDefinition d) {
        return d.steps().stream().map(CascadeDefinition.Step::waitAfter).toList();
    }

    @Test void reminders_go_24h_3h_and_1h_before_the_end_after_the_campaign_push() {
        var start = Instant.parse("2026-11-04T04:30:00Z");                 // 10:00 IST, two days before
        var end = Instant.parse("2026-11-06T18:29:00Z");                   // Dhanteras, 23:59 IST
        var d = CampaignCascades.countdown("campaign:x", "push_campaign_custom_v1", Duration.ofHours(12), start, end);

        assertEquals(List.of("push_campaign_custom_v1", "push_offer_countdown_v1", "push_offer_countdown_v1", "push_offer_countdown_v1"),
                d.steps().stream().map(CascadeDefinition.Step::templateKey).toList());
        assertEquals(Duration.between(start, end.minus(Duration.ofHours(24))), waits(d).get(0));
        assertEquals(Duration.ofHours(21), waits(d).get(1));
        assertEquals(Duration.ofHours(2), waits(d).get(2));
        assertEquals(Duration.ZERO, waits(d).get(3));
        assertEquals(Priority.LOW_INTENT, d.priority());
    }

    @Test void a_short_offer_keeps_only_the_reminders_still_ahead() {
        var start = Instant.parse("2026-11-06T14:00:00Z");
        var end = start.plus(Duration.ofHours(2));                          // the 3 h and 24 h marks have passed
        var d = CampaignCascades.countdown("campaign:x", "push_campaign_custom_v1", Duration.ofHours(12), start, end);
        assertEquals(2, d.steps().size());
        assertEquals(Duration.ofHours(1), waits(d).get(0));

        var tooClose = CampaignCascades.countdown("campaign:x", "push_campaign_custom_v1", Duration.ofHours(12),
                start, start.plus(Duration.ofMinutes(80)));                 // 1 h mark only 20 min after the start
        assertEquals(1, tooClose.steps().size(), "no reminder right on the heels of the first push");
    }
}
