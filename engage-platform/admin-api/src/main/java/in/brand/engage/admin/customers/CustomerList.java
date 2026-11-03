package in.brand.engage.admin.customers;

import in.brand.engage.persistence.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The customer base at a glance (owner, 2026-11-04): what the Shopify sync
 * brought in, and a masked list. First name, masked phone and the purchase
 * figures only: no full contact detail leaves this query, so the list cannot
 * export the base; the full number stays behind the counted reveal.
 *
 * <p>A customer is a live identity with a Shopify customer id. Orders are the
 * larger of Shopify's lifetime count and Engage's own; the last order the later
 * of the two (as the segment fields count them).
 */
final class CustomerList {

    static final int PAGE = 50;

    /** The list filters. */
    enum Filter { ALL, BUYERS, WHATSAPP, REACHABLE }

    private static final String BASE = """
            WITH c AS (
              SELECT i.id,
                     p.attrs->>'first_name' AS first_name,
                     p.attrs->>'city' AS city,
                     GREATEST(COALESCE((p.attrs->>'shopify_orders_count')::int, 0),
                              (SELECT count(*) FROM orders o WHERE o.identity_id = i.id AND o.cancelled_at IS NULL)) AS orders,
                     GREATEST((p.attrs->>'shopify_last_order_at')::timestamptz,
                              (SELECT max(o.created_at) FROM orders o WHERE o.identity_id = i.id AND o.cancelled_at IS NULL)) AS last_order_at,
                     (p.attrs->>'shopify_spent_paise')::bigint AS spent_paise,
                     (SELECT k.value FROM identity_keys k WHERE k.identity_id = i.id AND k.kind = 'phone'
                       ORDER BY k.verified DESC, k.last_seen DESC LIMIT 1) AS phone,
                     (SELECT cs.state::text FROM consent_current cs WHERE cs.identity_id = i.id
                         AND cs.channel = 'whatsapp' AND cs.purpose = 'marketing') AS wa_marketing
                FROM identities i
                LEFT JOIN profiles p ON p.identity_id = i.id
               WHERE i.merged_into IS NULL
                 AND EXISTS (SELECT 1 FROM identity_keys k WHERE k.identity_id = i.id AND k.kind = 'shopify_customer'))
            """;

    private CustomerList() {}

    /** Counts, and how the last sync went. */
    static Map<String, Object> summary(Connection c) throws SQLException {
        var out = new LinkedHashMap<String, Object>();
        try (var ps = Sql.prepare(c, BASE + """
                SELECT count(*), count(*) FILTER (WHERE phone IS NOT NULL),
                       count(*) FILTER (WHERE wa_marketing = 'granted'),
                       count(*) FILTER (WHERE orders > 0),
                       count(*) FILTER (WHERE orders > 0 AND wa_marketing = 'granted')
                  FROM c"""); var rs = ps.executeQuery()) {
            rs.next();
            out.put("customers", rs.getLong(1));
            out.put("withPhone", rs.getLong(2));
            out.put("whatsapp", rs.getLong(3));
            out.put("buyers", rs.getLong(4));
            out.put("reachableBuyers", rs.getLong(5));
        }
        try (var ps = Sql.prepare(c, """
                SELECT max(received_at), count(*) FILTER (WHERE processed_at IS NOT NULL),
                       count(*) FILTER (WHERE processed_at IS NULL AND last_error IS NULL),
                       count(*) FILTER (WHERE processed_at IS NULL AND last_error IS NOT NULL)
                  FROM webhook_inbox WHERE source = 'shopify' AND delivery_id LIKE 'sync:customer:%'""");
             var rs = ps.executeQuery()) {
            rs.next();
            var sync = new LinkedHashMap<String, Object>();
            sync.put("lastAt", rs.getObject(1, java.time.OffsetDateTime.class));
            sync.put("processed", rs.getLong(2));
            sync.put("pending", rs.getLong(3));
            sync.put("failed", rs.getLong(4));
            out.put("sync", sync);
        }
        return out;
    }

    /** One page, most recent buyers first, then everyone else by name. */
    static Map<String, Object> page(Connection c, Filter filter, int page) throws SQLException {
        var where = switch (filter) {
            case ALL -> "true";
            case BUYERS -> "orders > 0";
            case WHATSAPP -> "wa_marketing = 'granted'";
            case REACHABLE -> "orders > 0 AND wa_marketing = 'granted'";
        };
        long total;
        try (var ps = Sql.prepare(c, BASE + "SELECT count(*) FROM c WHERE " + where); var rs = ps.executeQuery()) {
            rs.next();
            total = rs.getLong(1);
        }
        var rows = new ArrayList<Map<String, Object>>();
        try (var ps = Sql.prepare(c, BASE + """
                SELECT id, first_name, city, orders, last_order_at, spent_paise, phone, wa_marketing
                  FROM c WHERE %s
                 ORDER BY last_order_at DESC NULLS LAST, lower(first_name) NULLS LAST, id
                 LIMIT ? OFFSET ?""".formatted(where), PAGE, (long) page * PAGE); var rs = ps.executeQuery()) {
            while (rs.next()) {
                var r = new LinkedHashMap<String, Object>();
                r.put("identityId", rs.getObject("id").toString());
                r.put("firstName", rs.getString("first_name"));
                r.put("city", rs.getString("city"));
                r.put("orders", rs.getLong("orders"));
                r.put("lastOrderAt", rs.getObject("last_order_at", java.time.OffsetDateTime.class));
                r.put("spentPaise", Sql.nullableLong(rs, "spent_paise"));
                var phone = rs.getString("phone");
                r.put("phone", phone == null ? null : CustomerQueries.mask("phone", phone));
                r.put("whatsapp", rs.getString("wa_marketing") == null ? "none" : rs.getString("wa_marketing"));
                rows.add(r);
            }
        }
        return Map.of("total", total, "page", page, "pageSize", PAGE, "rows", rows);
    }
}
