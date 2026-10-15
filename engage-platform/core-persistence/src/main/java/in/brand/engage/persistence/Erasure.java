package in.brand.engage.persistence;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DPDP erasure (V36 {@code erase_identity}): a person, everything merged into
 * them, and every copy of their phone, email and order data. Used by Shopify's
 * {@code customers/redact} and by the console's erase (OWNER). Runs in the
 * caller's transaction.
 */
@Singleton
public class Erasure {

    /** Sends and conversions of erased people. Identifies nobody. */
    public static final UUID TOMBSTONE = new UUID(0, 0);

    /**
     * The identities holding any of these keys. Null or blank values are
     * ignored; {@code phone} must already be normalised (91XXXXXXXXXX).
     */
    public Set<UUID> identitiesFor(Connection c, String shopifyCustomerId, String email, String phone) throws SQLException {
        var kinds = new ArrayList<String>();
        var values = new ArrayList<String>();
        add(kinds, values, "shopify_customer", shopifyCustomerId);
        add(kinds, values, "email", email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT));
        add(kinds, values, "phone", phone);
        add(kinds, values, "wa_id", phone);
        var out = new LinkedHashSet<UUID>();
        if (kinds.isEmpty()) return out;
        try (var ps = Sql.prepare(c, """
                SELECT DISTINCT k.identity_id FROM identity_keys k
                  JOIN unnest(CAST(? AS text[]), CAST(? AS text[])) AS w(kind, value)
                    ON k.kind = w.kind::key_kind AND k.value = w.value""",
                kinds.toArray(String[]::new), values.toArray(String[]::new));
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getObject(1, UUID.class));
        }
        return out;
    }

    private static void add(List<String> kinds, List<String> values, String kind, String value) {
        if (value != null && !value.isBlank()) {
            kinds.add(kind);
            values.add(value.strip());
        }
    }

    /**
     * Erases the person this identity belongs to.
     *
     * @return the counts as JSON ({@code {"identities": 0}} when it no longer exists)
     */
    public String erase(Connection c, UUID identityId, String requestRef, UUID actor) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT erase_identity(?, ?, ?)::text", identityId, requestRef, actor);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Phone, email and raw data of these orders, whoever they belong to (Shopify's orders_to_redact). */
    public int scrubOrders(Connection c, List<String> orderIds, String requestRef) throws SQLException {
        if (orderIds.isEmpty()) return 0;
        try (var ps = Sql.prepare(c, "SELECT privacy_scrub_orders(CAST(? AS text[]), ?)",
                orderIds.toArray(String[]::new), requestRef);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
