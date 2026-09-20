package in.brand.engage.ingest.db;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin wrapper over the {@code resolve_identity(jsonb)} database function (V6),
 * where the merge policy and its concurrency control live and are tested.
 *
 * <p>The key array is assembled with jsonb_build_object and bound parameters,
 * never by string concatenation, so an email containing a quote cannot break
 * or inject into the JSON.
 */
@Singleton
public class IdentityResolver {

    /** Trust levels understood by resolve_identity. See V6 for the rules. */
    public enum Trust { STRONG, SESSION, BUYER, WEAK }

    public record Key(String kind, String value, boolean verified, Trust trust) {

        public static Key verified(String kind, String value) {
            return new Key(kind, value, true, Trust.STRONG);
        }

        public static Key session(String kind, String value) {
            return new Key(kind, value, false, Trust.SESSION);
        }

        /** The buyer's own contact phone/email: may absorb a soft identity. */
        public static Key buyer(String kind, String value) {
            return new Key(kind, value, false, Trust.BUYER);
        }

        /** e.g. a shipping-address phone, which may be a gift recipient's. Never merges. */
        public static Key weak(String kind, String value) {
            return new Key(kind, value, false, Trust.WEAK);
        }
    }

    /** Returns the identity, or null when no usable key was supplied. */
    public UUID resolve(Connection c, List<Key> keys) throws SQLException {
        var usable = keys.stream().filter(k -> k != null && k.value() != null && !k.value().isBlank()).toList();
        if (usable.isEmpty()) return null;

        var sql = new StringBuilder("SELECT resolve_identity(jsonb_build_array(");
        var params = new ArrayList<Object>();
        for (int i = 0; i < usable.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("jsonb_build_object('kind', ?::text, 'value', ?::text, 'verified', ?::boolean, 'trust', ?::text)");
            var k = usable.get(i);
            params.add(k.kind());
            params.add(k.value());
            params.add(k.verified());
            params.add(k.trust() == Trust.BUYER ? "buyer" : null);
        }
        sql.append("))");

        try (var ps = Sql.prepare(c, sql.toString(), params.toArray()); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }
}
