package in.brand.engage.persistence;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes to {@code events}. Journeys (Phase 5) react to these, never to raw
 * webhooks, which keeps sources swappable and makes replay possible.
 * The dedupe key makes a re-processed webhook a no-op.
 */
@Singleton
public class EventWriter {

    public void write(Connection c, UUID identityId, String name, String source, String dedupeKey,
                      Map<String, Object> props) throws SQLException {
        var sql = new StringBuilder("""
                INSERT INTO events (identity_id, name, props, source, dedupe_key)
                VALUES (?, ?, jsonb_strip_nulls(jsonb_build_object(""");
        var params = new ArrayList<Object>();
        params.add(identityId);
        params.add(name);
        int i = 0;
        for (var e : new LinkedHashMap<>(props).entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append("?::text, ").append(cast(e.getValue()));
            params.add(e.getKey());
            params.add(e.getValue());
        }
        sql.append(")), ?, ?) ON CONFLICT (dedupe_key) DO NOTHING");
        params.add(source);
        params.add(dedupeKey);
        Sql.update(c, sql.toString(), params.toArray());
    }

    private static String cast(Object v) {
        return switch (v) {
            case Long l -> "?::bigint";
            case Integer n -> "?::int";
            case Boolean b -> "?::boolean";
            case null, default -> "?::text";
        };
    }
}
