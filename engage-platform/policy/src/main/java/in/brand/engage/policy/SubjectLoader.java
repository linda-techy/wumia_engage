package in.brand.engage.policy;

import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads a {@link Subject} with one SQL round trip ({@code subject_load.sql}). */
@Singleton
public class SubjectLoader {

    public Subject load(Connection c, UUID identityId, Instant now, long staleDays) throws SQLException {
        var staleBefore = now.minus(Duration.ofDays(staleDays));
        try (var ps = Sql.prepare(c, SqlFiles.get("subject_load.sql"),
                identityId, now.atOffset(ZoneOffset.UTC), staleBefore.atOffset(ZoneOffset.UTC));
             var rs = ps.executeQuery()) {
            rs.next();

            var suppressions = new EnumMap<Channel, String>(Channel.class);
            for (var s : strings(rs, "suppressions")) {
                int i = s.indexOf(':');
                suppressions.putIfAbsent(Channel.fromDb(s.substring(0, i)), s.substring(i + 1));
            }
            var capability = new EnumMap<Channel, String>(Channel.class);
            for (var s : strings(rs, "capability")) {
                int i = s.indexOf(':');
                capability.put(Channel.fromDb(s.substring(0, i)), s.substring(i + 1));
            }

            var devices = new ArrayList<Subject.Device>();
            var ids = (Long[]) array(rs, "device_ids", new Long[0]);
            var tokens = (String[]) array(rs, "device_tokens", new String[0]);
            var platforms = (String[]) array(rs, "device_platforms", new String[0]);
            var stale = (Boolean[]) array(rs, "device_stale", new Boolean[0]);
            for (int i = 0; i < ids.length; i++) {
                devices.add(new Subject.Device(ids[i], tokens[i], platforms[i], stale[i]));
            }

            return new Subject(identityId, new HashSet<>(strings(rs, "grants")), Map.copyOf(suppressions),
                    List.copyOf(devices), Map.copyOf(capability),
                    instant(rs, "wa_backoff_until"), instant(rs, "wa_window_until"),
                    rs.getString("locale"), rs.getString("phone"), rs.getString("email"));
        }
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        return List.of((String[]) array(rs, column, new String[0]));
    }

    private static Object array(ResultSet rs, String column, Object empty) throws SQLException {
        Array a = rs.getArray(column);
        return a == null ? empty : a.getArray();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
