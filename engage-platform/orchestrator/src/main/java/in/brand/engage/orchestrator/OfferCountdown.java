package in.brand.engage.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * An offer's countdown (V44), for push campaigns with an end. The variables
 * come from the campaign:
 *
 * <ul>
 * <li>{@code ends_at}: ISO instant of the offer's end. Each push gets
 *     {@code time_left} for that moment, and a TTL that never outlives the offer;
 *     after the end nothing is sent.</li>
 * <li>{@code countdown_image}: the poster with the time-left band, drawn by
 *     ingest-api when the phone fetches it.</li>
 * </ul>
 *
 * The push data carries {@link #ENDS_AT} (epoch milliseconds) for whoever draws the
 * notification: the web service worker, or a native app, which can run a real
 * ticking timer (Android's chronometer counting down to it).
 */
public final class OfferCountdown {

    public static final String VAR_ENDS_AT = "ends_at";
    public static final String VAR_TIME_LEFT = "time_left";
    public static final String VAR_IMAGE = "countdown_image";
    /** The push data field: epoch milliseconds of the end. */
    public static final String ENDS_AT = "ends_at";
    /** Operators write this in a campaign's own title or text; it becomes e.g. "2h 15m". */
    public static final String TOKEN = "{time_left}";

    private OfferCountdown() {}

    public static Optional<Instant> endsAt(Map<String, String> vars) {
        var v = vars.get(VAR_ENDS_AT);
        if (v == null || v.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(v.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** "3 days", "1 day 5h", "5h 12m", "45 min": short enough for a 40-character title. */
    public static String timeLeft(Duration left) {
        long minutes = Math.max(1, (left.getSeconds() + 59) / 60);      // round up: never say 0
        long days = minutes / (24 * 60);
        long hours = (minutes % (24 * 60)) / 60;
        long mins = minutes % 60;
        if (days >= 2) return days + " days";
        if (days == 1) return hours == 0 ? "1 day" : "1 day " + hours + "h";
        if (hours > 0) return mins == 0 ? hours + "h" : hours + "h " + mins + "m";
        return mins + " min";
    }

    /** The operator's own words with {@link #TOKEN} filled. */
    public static String fill(String text, String timeLeft) {
        return text == null ? null : text.replace(TOKEN, timeLeft);
    }
}
