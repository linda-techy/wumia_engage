package in.brand.engage.worker.inbox;

import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/** The inbox's Settings keys (V50), read on every pass like every other config value. */
@Singleton
public class InboxSettings {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Support hours in IST; {@code from > to} wraps midnight. */
    public record Hours(LocalTime from, LocalTime to) {

        public boolean open(Instant at) {
            var t = LocalTime.ofInstant(at, IST);
            return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        }

        /** "10 AM", "7:30 PM". */
        static String label(LocalTime t) {
            int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
            return h + (t.getMinute() == 0 ? "" : ":%02d".formatted(t.getMinute())) + (t.getHour() < 12 ? " AM" : " PM");
        }

        public String opens() {
            return label(from);
        }

        public String closes() {
            return label(to);
        }
    }

    static final Hours DEFAULT_HOURS = new Hours(LocalTime.of(10, 0), LocalTime.of(19, 0));

    private final JsonMapper json;

    public InboxSettings(JsonMapper json) {
        this.json = json;
    }

    public Hours hours(Connection c) throws SQLException {
        var raw = value(c, "inbox.business_hours");
        if (raw == null) return DEFAULT_HOURS;
        try {
            Map<String, Object> m = json.readValue(raw, Argument.mapOf(String.class, Object.class));
            return new Hours(LocalTime.parse(String.valueOf(m.get("from"))), LocalTime.parse(String.valueOf(m.get("to"))));
        } catch (IOException | RuntimeException e) {
            return DEFAULT_HOURS;
        }
    }

    public boolean autoReply(Connection c) throws SQLException {
        return !"false".equals(value(c, "inbox.auto_reply"));
    }

    public String shopUrl(Connection c) throws SQLException {
        var raw = value(c, "inbox.shop_url");
        try {
            var s = raw == null ? null : json.readValue(raw, String.class);
            return s != null && s.startsWith("https://") ? s : "https://www.wumika.com/collections/all?sort_by=best-selling";
        } catch (IOException e) {
            return "https://www.wumika.com/collections/all?sort_by=best-selling";
        }
    }

    /** The first-order code the storefront's WhatsApp popup promises (the welcome series' code), or null. */
    public String welcomeCode(Connection c) throws SQLException {
        return string(value(c, "journey.welcome.discount_code"));
    }

    /** "10% off", or null. */
    public String welcomeLabel(Connection c) throws SQLException {
        return string(value(c, "journey.welcome.discount_label"));
    }

    private String string(String raw) {
        try {
            var s = raw == null ? null : json.readValue(raw, String.class);
            return s == null || s.isBlank() ? null : s.strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** The global value as JSON text, or the key's default. */
    private static String value(Connection c, String key) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE((SELECT value::text FROM config_current WHERE key = ? AND selector = '*' LIMIT 1),
                                (SELECT default_value::text FROM config_keys WHERE key = ?))""", key, key);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
