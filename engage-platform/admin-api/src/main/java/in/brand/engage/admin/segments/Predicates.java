package in.brand.engage.admin.segments;

import in.brand.engage.admin.web.Problems;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The whitelist (phase-6 §3): every field a segment may test, the operators
 * each accepts, and the SQL it becomes. Field names and operators select a
 * fixed SQL fragment from this class; values only ever become bind
 * parameters. Each fragment is a condition on {@code i.id} (an identity), an
 * {@code EXISTS} or a scalar subquery, so combining them never multiplies rows.
 *
 * <p>Fields whose data is not collected yet are listed as unavailable and
 * refused with a 400 naming what is missing, rather than silently matching
 * nobody.
 */
public final class Predicates {

    /** A compiled condition: SQL with {@code ?} placeholders, and their values in order. */
    public record Fragment(String sql, List<Object> params) {}

    /** What the builder UI renders. {@code note} says why an unavailable field is unavailable. */
    @Serdeable
    public record Field(String name, String label, List<String> ops, String valueKind, boolean available, String note) {}

    private interface Compile {
        Fragment apply(String op, Object value);
    }

    private record Def(Field field, Compile compile) {}

    static final int MAX_LIST = 100;
    static final int MAX_TEXT = 100;
    static final Period MAX_PERIOD = Period.ofYears(10);

    /** Orders that count as purchases: cancelled ones do not. */
    private static final String ORDERS = "FROM orders o WHERE o.identity_id = i.id AND o.cancelled_at IS NULL";
    private static final String BOUGHT = """
            EXISTS (SELECT 1 FROM orders o JOIN order_lines l ON l.order_id = o.id
                      JOIN products p ON p.product_id = l.product_id
                     WHERE o.identity_id = i.id AND o.cancelled_at IS NULL AND %s)""";
    /** A cart still open: items in it, not converted, touched in the last 30 days. */
    private static final String OPEN_CART = """
            FROM carts c WHERE c.identity_id = i.id AND c.converted_at IS NULL AND c.item_count > 0
               AND c.updated_at > now() - interval '30 days'""";
    private static final String PROFILE = "EXISTS (SELECT 1 FROM profiles pf WHERE pf.identity_id = i.id AND %s)";

    private static final UnaryOperator<String> LOWER = s -> s.toLowerCase(Locale.ROOT);
    private static final UnaryOperator<String> UPPER = s -> s.toUpperCase(Locale.ROOT);

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        // ---- orders ----
        def("orders_count", "Orders placed", List.of("eq", "gte", "lte", "gt", "lt"), "integer",
                (op, v) -> new Fragment("(SELECT count(*) " + ORDERS + ") " + comparison(op) + " ?",
                        List.of(integer(v))));
        def("last_order_at", "Last order", List.of("older_than", "within"), "period",
                (op, v) -> {
                    var since = since(v);
                    return op.equals("within")
                            ? new Fragment("EXISTS (SELECT 1 " + ORDERS + " AND o.created_at >= ?)", List.of(since))
                            : new Fragment("(EXISTS (SELECT 1 " + ORDERS + ") AND NOT EXISTS (SELECT 1 " + ORDERS
                                    + " AND o.created_at >= ?))", List.of(since));
                });
        def("bought_product_type", "Bought product type", List.of("in"), "text_list",
                (op, v) -> new Fragment(BOUGHT.formatted("p.product_type = ANY(?)"), param(texts(v, LOWER))));
        def("bought_product", "Bought product (handle)", List.of("in"), "text_list",
                (op, v) -> new Fragment(BOUGHT.formatted("p.handle = ANY(?)"), param(texts(v, LOWER))));
        def("bought_size", "Bought size", List.of("in"), "text_list",
                (op, v) -> new Fragment(BOUGHT.formatted(
                        "p.size_position IS NOT NULL AND upper(btrim(split_part(l.variant_title, ' / ', p.size_position))) = ANY(?)"),
                        param(texts(v, UPPER))));
        unavailable("bought_collection", "Bought from collection", List.of("in"), "text_list",
                "Collection membership is in no Shopify webhook; it needs an Admin API collection sync.");
        unavailable("aov_band", "Average order value band", List.of("in"), "text_list",
                "Needs the nightly profile job (P5 profile_recompute).");
        unavailable("net_margin_band", "Net margin band", List.of("in"), "text_list",
                "Needs the nightly profile job and product costs.");

        // ---- profile ----
        def("shopify_tag", "Shopify customer tag", List.of("in"), "text_list",
                (op, v) -> new Fragment(PROFILE.formatted("jsonb_exists_any(pf.attrs->'shopify_tags', ?)"),
                        param(texts(v, LOWER))));
        def("state", "State (code)", List.of("in"), "text_list",
                (op, v) -> new Fragment(PROFILE.formatted("upper(pf.attrs->>'state') = ANY(?)"), param(texts(v, UPPER))));
        def("city", "City", List.of("in"), "text_list",
                (op, v) -> new Fragment(PROFILE.formatted("lower(pf.attrs->>'city') = ANY(?)"), param(texts(v, LOWER))));
        unavailable("city_tier", "City tier", List.of("in"), "text_list",
                "Needs a city-to-tier mapping; use city or state meanwhile.");

        // ---- carts and waitlist ----
        def("has_open_cart", "Has an open cart", List.of("is"), "boolean",
                (op, v) -> exists("EXISTS (SELECT 1 " + OPEN_CART + ")", bool(v)));
        def("cart_value", "Open cart value (₹)", List.of("gte", "lte"), "rupees",
                (op, v) -> new Fragment("EXISTS (SELECT 1 " + OPEN_CART + " AND c.total_paise " + comparison(op) + " ?)",
                        List.of(paise(v))));
        def("waitlisted_variant", "Waiting for variant (id)", List.of("in"), "text_list",
                (op, v) -> new Fragment("""
                        EXISTS (SELECT 1 FROM stock_waitlist w WHERE w.identity_id = i.id AND w.notified_at IS NULL
                                   AND w.variant_id = ANY(?))""", param(texts(v, UnaryOperator.identity()))));
        def("waitlisted_product", "Waiting for product (handle)", List.of("in"), "text_list",
                (op, v) -> new Fragment("""
                        EXISTS (SELECT 1 FROM stock_waitlist w WHERE w.identity_id = i.id AND w.notified_at IS NULL
                                   AND w.product_handle = ANY(?))""", param(texts(v, LOWER))));

        // ---- reachability and consent ----
        def("push_reachable", "Push reachable (fresh token)", List.of("is"), "boolean",
                (op, v) -> exists("EXISTS (SELECT 1 FROM device_health d WHERE d.identity_id = i.id AND d.state = 'active')",
                        bool(v)));
        def("wa_capable", "WhatsApp capability", List.of("is"), "enum:CAPABLE,UNKNOWN,INCAPABLE",
                (op, v) -> new Fragment("""
                        COALESCE((SELECT cc.state FROM channel_capability cc
                                   WHERE cc.identity_id = i.id AND cc.channel = 'whatsapp'), 'UNKNOWN') = ?""",
                        List.of(oneOf(v, List.of("CAPABLE", "UNKNOWN", "INCAPABLE")))));
        for (var ch : List.of("whatsapp", "push", "email", "sms")) {
            var name = (ch.equals("whatsapp") ? "wa" : ch) + "_marketing";
            def(name, "Marketing consent: " + ch, List.of("is"), "boolean",
                    (op, v) -> exists("""
                            EXISTS (SELECT 1 FROM consent_current cs WHERE cs.identity_id = i.id
                                       AND cs.channel = '%s' AND cs.purpose = 'marketing' AND cs.state = 'granted')"""
                            .formatted(ch), bool(v)));
        }
    }

    private Predicates() {}

    public static List<Field> fields() {
        return DEFS.values().stream().map(Def::field).toList();
    }

    /** 400 for an unknown field, an unavailable one, a wrong operator or a bad value. */
    public static Fragment compile(String field, String op, Object value) {
        var def = DEFS.get(field);
        if (def == null) throw Problems.badRequest("unknown segment field " + quoted(field));
        if (!def.field().available()) throw Problems.badRequest(field + " is not available yet: " + def.field().note());
        if (!def.field().ops().contains(op)) {
            throw Problems.badRequest(field + " takes " + def.field().ops() + ", not " + quoted(op));
        }
        return def.compile().apply(op, value);
    }

    /* ------------------------------------------------------------------ */

    private static void def(String name, String label, List<String> ops, String kind, Compile compile) {
        DEFS.put(name, new Def(new Field(name, label, ops, kind, true, null), compile));
    }

    private static void unavailable(String name, String label, List<String> ops, String kind, String note) {
        DEFS.put(name, new Def(new Field(name, label, ops, kind, false, note), null));
    }

    /** One bind parameter. {@code List.of(String[])} would spread the array into separate elements. */
    private static List<Object> param(Object value) {
        return java.util.Collections.singletonList(value);
    }

    private static Fragment exists(String sql, boolean wanted) {
        return new Fragment(wanted ? sql : "NOT " + sql, List.of());
    }

    /** The operator symbol for a whitelisted op. Never derived from input text. */
    private static String comparison(String op) {
        return switch (op) {
            case "eq" -> "=";
            case "gte" -> ">=";
            case "lte" -> "<=";
            case "gt" -> ">";
            case "lt" -> "<";
            default -> throw new IllegalStateException(op);
        };
    }


    private static String[] texts(Object v, UnaryOperator<String> norm) {
        if (!(v instanceof List<?> list) || list.isEmpty() || list.size() > MAX_LIST) {
            throw Problems.badRequest("value is a list of 1 to " + MAX_LIST + " strings");
        }
        var out = new ArrayList<String>(list.size());
        for (var item : list) {
            if (!(item instanceof String s) || s.isBlank() || s.length() > MAX_TEXT) {
                throw Problems.badRequest("each value is a non-empty string of at most " + MAX_TEXT + " characters");
            }
            out.add(norm.apply(s.strip()));
        }
        return out.stream().distinct().toArray(String[]::new);
    }

    private static Long integer(Object v) {
        if (!(v instanceof Number n)) throw Problems.badRequest("value is a whole number");
        var d = new BigDecimal(n.toString());
        if (d.stripTrailingZeros().scale() > 0 || d.signum() < 0 || d.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw Problems.badRequest("value is a whole number from 0 to 1000000");
        }
        return d.longValue();
    }

    private static Long paise(Object v) {
        if (!(v instanceof Number n)) throw Problems.badRequest("value is an amount in rupees");
        var d = new BigDecimal(n.toString());
        if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(10_000_000)) > 0 || d.scale() > 2) {
            throw Problems.badRequest("value is rupees from 0 to 1,00,00,000 with at most two decimals");
        }
        return d.movePointRight(2).longValueExact();
    }

    private static boolean bool(Object v) {
        if (!(v instanceof Boolean b)) throw Problems.badRequest("value is true or false");
        return b;
    }

    private static String oneOf(Object v, List<String> options) {
        if (!(v instanceof String s) || !options.contains(s)) throw Problems.badRequest("value is one of " + options);
        return s;
    }

    /** {@code P60D} → now minus 60 days. Calendar periods only: java.time.Period, as the plan says. */
    private static OffsetDateTime since(Object v) {
        if (!(v instanceof String s)) throw Problems.badRequest("value is an ISO-8601 period such as P60D");
        Period p;
        try {
            p = Period.parse(s);
        } catch (DateTimeParseException e) {
            throw Problems.badRequest("value is an ISO-8601 period such as P60D");
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (p.isNegative() || p.isZero() || now.minus(p).isBefore(now.minus(MAX_PERIOD))) {
            throw Problems.badRequest("the period is positive and at most 10 years");
        }
        return now.minus(p);
    }

    private static String quoted(String s) {
        var t = s == null ? "null" : s.length() > 40 ? s.substring(0, 40) + "…" : s;
        return "'" + t + "'";
    }
}
