package in.brand.engage.policy;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The config in force at one moment, persisted as a {@code config_snapshots}
 * row whose id every decision records.
 *
 * <p>Values are keyed {@code key|selector}. A lookup takes the exact selector,
 * else {@code *}; defaults are resolved in as {@code *} when no version exists.
 * Values hold the JSON scalar as text ({@code 3}, {@code true}), or the JSON
 * document for objects ({@code quiet_hours}).
 */
public record ConfigSnapshot(long id, Map<String, String> values) {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Pattern CAP_KEY = Pattern.compile("cap\\.([a-z]+)\\.([a-z]+)\\.(\\d+)d");
    private static final Pattern HH_MM = Pattern.compile("\"(from|to)\"\\s*:\\s*\"(\\d{2}:\\d{2})\"");

    public ConfigSnapshot {
        values = Map.copyOf(values);
    }

    /** @throws IllegalStateException if neither the selector nor {@code *} has a value */
    public String text(String key, String selector) {
        var v = selector == null ? null : values.get(key + "|" + selector);
        if (v == null) v = values.get(key + "|*");
        if (v == null) throw new IllegalStateException("config key " + key + " has no value");
        return v;
    }

    public long longValue(String key, String selector) {
        return Long.parseLong(text(key, selector));
    }

    public boolean boolValue(String key, String selector) {
        return Boolean.parseBoolean(text(key, selector));
    }

    public BigDecimal decimalValue(String key, String selector) {
        return new BigDecimal(text(key, selector));
    }

    /** A rate or budget key that may not exist for this channel/category. */
    public long longOrZero(String key) {
        var v = values.get(key + "|*");
        return v == null ? 0 : Long.parseLong(v);
    }

    public QuietHours quietHours() {
        var json = text("quiet_hours", "*");
        LocalTime from = null, to = null;
        var m = HH_MM.matcher(json);
        while (m.find()) {
            if (m.group(1).equals("from")) from = LocalTime.parse(m.group(2));
            else to = LocalTime.parse(m.group(2));
        }
        if (from == null || to == null) throw new IllegalStateException("quiet_hours is not {from,to}: " + json);
        return new QuietHours(from, to);
    }

    /** Every {@code cap.<channel>.<category>.<N>d} key for this channel and category. */
    public List<Cap> capsFor(Channel channel, Category category) {
        var caps = new ArrayList<Cap>();
        for (var e : values.entrySet()) {
            var key = e.getKey().substring(0, e.getKey().indexOf('|'));
            if (!e.getKey().endsWith("|*")) continue;
            var m = CAP_KEY.matcher(key);
            if (m.matches() && m.group(1).equals(channel.dbName()) && m.group(2).equals(category.dbName())) {
                caps.add(new Cap(key, Duration.ofDays(Long.parseLong(m.group(3))), Long.parseLong(e.getValue())));
            }
        }
        return caps;
    }

    public record Cap(String key, Duration window, long max) {}

    /**
     * A daily window in IST. {@code from} is inside, {@code to} is outside,
     * and it may wrap midnight (21:00–09:00). Equal ends mean no quiet hours.
     */
    public record QuietHours(LocalTime from, LocalTime to) {

        public boolean contains(Instant t) {
            var lt = t.atZone(IST).toLocalTime();
            if (from.equals(to)) return false;
            return from.isBefore(to)
                    ? !lt.isBefore(from) && lt.isBefore(to)
                    : !lt.isBefore(from) || lt.isBefore(to);
        }

        /** The first instant after {@code t} at which the window has closed. */
        public Instant nextOpen(Instant t) {
            ZonedDateTime local = t.atZone(IST);
            var candidate = local.toLocalDate().atTime(to).atZone(IST);
            if (!candidate.isAfter(local)) candidate = candidate.plusDays(1);
            return candidate.toInstant();
        }
    }
}
