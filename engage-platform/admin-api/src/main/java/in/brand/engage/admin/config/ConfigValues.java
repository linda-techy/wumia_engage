package in.brand.engage.admin.config;

import in.brand.engage.admin.web.Problems;
import in.brand.engage.core.messaging.Channel;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side validation of a config write: the selector against the key's
 * scope, the value against its {@code value_type} and {@code json_schema}.
 * The console validates for ergonomics; this is the check that counts
 * (02-config-and-settings.md: the API will eventually be called by a script).
 *
 * <p>The schema support is a subset: {@code type}, {@code minimum},
 * {@code maximum}, {@code enum}, {@code pattern}, {@code minLength},
 * {@code maxLength}. Any other keyword fails closed: a bound nobody enforces
 * is worse than a write that is refused until the validator learns it.
 */
public final class ConfigValues {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Set<String> KEYWORDS =
            Set.of("type", "minimum", "maximum", "enum", "pattern", "minLength", "maxLength", "description");

    private ConfigValues() {}

    /** The selector, normalised: '*' when blank. 400 when the scope does not allow it. */
    public static String selector(String scope, String selector) {
        var s = selector == null || selector.isBlank() ? "*" : selector.strip();
        if (s.equals("*")) return s;
        switch (scope) {
            case "GLOBAL" -> throw Problems.badRequest("a GLOBAL key takes selector *");
            case "CHANNEL" -> {
                if (Arrays.stream(Channel.values()).noneMatch(ch -> ch.dbName().equals(s))) {
                    throw Problems.badRequest("unknown channel " + s);
                }
            }
            default -> {   // JOURNEY, TEMPLATE
                if (!KEY.matcher(s).matches()) throw Problems.badRequest("selector must be * or a lowercase key");
            }
        }
        return s;
    }

    /** 400 with the reason when {@code value} is not a valid value for the key. */
    public static void validate(String valueType, Map<String, Object> schema, Object value) {
        if (value == null) throw Problems.badRequest("value is required");
        switch (valueType) {
            case "INT" -> {
                if (!isInteger(value)) throw Problems.badRequest("value must be a whole number");
            }
            case "DECIMAL" -> {
                if (!(value instanceof Number)) throw Problems.badRequest("value must be a number");
            }
            case "BOOL" -> {
                if (!(value instanceof Boolean)) throw Problems.badRequest("value must be true or false");
            }
            case "STRING" -> {
                if (!(value instanceof String)) throw Problems.badRequest("value must be text");
            }
            case "ENUM" -> {
                if (!(value instanceof String)) throw Problems.badRequest("value must be one of the listed options");
                if (schema == null || !(schema.get("enum") instanceof List<?>)) {
                    throw new IllegalStateException("ENUM key without a json_schema enum");
                }
            }
            case "TIME_RANGE" -> timeRange(value);
            case "JSON" -> { }
            default -> throw new IllegalStateException("unknown value_type " + valueType);
        }
        if (schema != null) checkSchema(schema, value);
    }

    private static void timeRange(Object value) {
        if (!(value instanceof Map<?, ?> m) || !m.keySet().equals(Set.of("from", "to"))
                || !(m.get("from") instanceof String from) || !(m.get("to") instanceof String to)) {
            throw Problems.badRequest("value must be {\"from\":\"HH:MM\",\"to\":\"HH:MM\"}");
        }
        if (!HH_MM.matcher(from).matches() || !HH_MM.matcher(to).matches()) {
            throw Problems.badRequest("times are 24-hour HH:MM, IST");
        }
        if (from.equals(to)) throw Problems.badRequest("from and to must differ");
    }

    private static void checkSchema(Map<String, Object> schema, Object value) {
        for (var keyword : schema.keySet()) {
            if (!KEYWORDS.contains(keyword)) {
                throw new IllegalStateException("json_schema keyword '" + keyword + "' is not supported by the validator");
            }
        }
        var type = schema.get("type");
        if (type != null && !hasType(String.valueOf(type), value)) {
            throw Problems.badRequest("value must be of type " + type);
        }
        if (schema.containsKey("minimum") && compare(value, schema.get("minimum")) < 0) {
            throw Problems.badRequest("value must be at least " + schema.get("minimum"));
        }
        if (schema.containsKey("maximum") && compare(value, schema.get("maximum")) > 0) {
            throw Problems.badRequest("value must be at most " + schema.get("maximum"));
        }
        if (schema.get("enum") instanceof List<?> options && options.stream().noneMatch(o -> same(o, value))) {
            throw Problems.badRequest("value must be one of " + options);
        }
        if (value instanceof String s) {
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).matches()) {
                throw Problems.badRequest("value does not match " + p);
            }
            if (schema.get("minLength") instanceof Number n && s.codePointCount(0, s.length()) < n.intValue()) {
                throw Problems.badRequest("value is shorter than " + n);
            }
            if (schema.get("maxLength") instanceof Number n && s.codePointCount(0, s.length()) > n.intValue()) {
                throw Problems.badRequest("value is longer than " + n);
            }
        }
    }

    private static boolean hasType(String type, Object value) {
        return switch (type) {
            case "integer" -> isInteger(value);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> throw new IllegalStateException("json_schema type '" + type + "' is not supported");
        };
    }

    private static boolean isInteger(Object value) {
        if (!(value instanceof Number n)) return false;
        var d = decimal(n);
        return d.stripTrailingZeros().scale() <= 0
                && d.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                && d.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0;
    }

    private static int compare(Object value, Object bound) {
        if (!(value instanceof Number v) || !(bound instanceof Number b)) {
            throw Problems.badRequest("value must be a number");
        }
        return decimal(v).compareTo(decimal(b));
    }

    private static boolean same(Object option, Object value) {
        if (option instanceof Number a && value instanceof Number b) return decimal(a).compareTo(decimal(b)) == 0;
        return option.equals(value);
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
    }
}
