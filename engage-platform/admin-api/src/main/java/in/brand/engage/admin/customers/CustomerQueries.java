package in.brand.engage.admin.customers;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.privacy.Masks;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries behind the customer screens. Exact lookup only (no
 * partial match, no listing), and contact keys masked unless the caller asks
 * for {@link #keys(Connection, UUID, boolean) unmasked} under an audit row.
 */
@Singleton
public class CustomerQueries {

    /** A full email (lower-cased) or a normalisable Indian mobile; empty for anything else. */
    public static Optional<String> normalise(String q) {
        if (q == null || q.isBlank()) return Optional.empty();
        var t = q.strip();
        if (t.contains("@")) {
            int at = t.indexOf('@');
            return at > 0 && at < t.length() - 1 && t.indexOf('.', at) > at
                    ? Optional.of(t.toLowerCase(Locale.ROOT)) : Optional.empty();
        }
        return Msisdn.normalise(t);
    }

    /** The identity for an exact key; empty when none or ambiguous. */
    public Optional<UUID> lookup(Connection c, String key) throws SQLException {
        var found = new ArrayList<UUID>();
        try (var ps = Sql.prepare(c, SqlFiles.get("customer_lookup.sql"), key); var rs = ps.executeQuery()) {
            while (rs.next()) found.add(rs.getObject(1, UUID.class));
        }
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    public boolean exists(Connection c, UUID id) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT 1 FROM identities WHERE id = ?", id); var rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    /** Identity keys; contact values and opaque ids masked unless {@code unmasked}. */
    public List<Map<String, Object>> keys(Connection c, UUID id, boolean unmasked) throws SQLException {
        var rows = rows(c, "customer_keys.sql", id);
        if (!unmasked) {
            for (var r : rows) r.put("value", mask((String) r.get("kind"), (String) r.get("value")));
        }
        return rows;
    }

    static String mask(String kind, String value) {
        return switch (kind) {
            case "email" -> Masks.email(value);
            case "phone" -> Msisdn.mask(value);
            case "fcm_token", "anon", "cart_token", "checkout_token" -> Masks.opaque(value);
            default -> value;               // shopify_customer and the like: ids, not contact details
        };
    }

    public Map<String, Object> view(Connection c, UUID id) throws SQLException {
        var view = new LinkedHashMap<String, Object>();
        view.put("identityId", id.toString());
        var profile = rows(c, "customer_profile.sql", id);
        view.put("profile", profile.isEmpty() ? Map.of() : profile.getFirst());
        view.put("keys", keys(c, id, false));
        view.put("consent", rows(c, "customer_consent.sql", id));
        view.put("orders", rows(c, "customer_orders.sql", id));
        view.put("checkouts", maskTokens(rows(c, "customer_checkouts.sql", id)));
        view.put("payments", rows(c, "customer_payments.sql", id));
        view.put("shipments", rows(c, "customer_shipments.sql", id));
        view.put("devices", rows(c, "customer_devices.sql", id));
        view.put("sends", rows(c, "customer_sends.sql", id));
        return view;
    }

    private static List<Map<String, Object>> maskTokens(List<Map<String, Object>> rows) {
        for (var r : rows) r.put("token", Masks.opaque((String) r.get("token")));
        return rows;
    }

    /** Every row as {camelCaseColumn: value}; jsonb columns selected as text stay strings. */
    static List<Map<String, Object>> rows(Connection c, String sqlFile, UUID id) throws SQLException {
        var sql = SqlFiles.get(sqlFile);
        int params = (int) sql.chars().filter(ch -> ch == '?').count();
        var args = new Object[params];
        java.util.Arrays.fill(args, id);
        var out = new ArrayList<Map<String, Object>>();
        try (var ps = Sql.prepare(c, sql, args); var rs = ps.executeQuery()) {
            var meta = rs.getMetaData();
            while (rs.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(camel(meta.getColumnLabel(i)), value(rs, i, meta.getColumnType(i)));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static Object value(ResultSet rs, int i, int type) throws SQLException {
        return switch (type) {
            case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP -> rs.getObject(i, java.time.OffsetDateTime.class);
            case Types.OTHER -> rs.getString(i);                 // uuid and enums as text
            default -> {
                var v = rs.getObject(i);
                yield v instanceof UUID u ? u.toString() : v;
            }
        };
    }

    private static String camel(String column) {
        var sb = new StringBuilder();
        boolean up = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
