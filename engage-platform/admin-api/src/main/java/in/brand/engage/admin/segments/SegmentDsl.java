package in.brand.engage.admin.segments;

import in.brand.engage.admin.web.Problems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A segment definition as the browser sends it, parsed into a closed tree.
 *
 * <pre>
 * { "all": [ ... ] }   every child matches
 * { "any": [ ... ] }   at least one child matches
 * { "not": { ... } }   the child does not match
 * { "field": "state", "op": "in", "value": ["KL", "TN"] }
 * </pre>
 *
 * Parsing checks shape only: depth at most 5, at most 30 predicates, no
 * unknown keys. Whether a field and operator exist is {@link Predicates}'
 * business, at compile time.
 */
public sealed interface SegmentDsl {

    int MAX_DEPTH = 5;
    int MAX_PREDICATES = 30;

    record All(List<SegmentDsl> of) implements SegmentDsl {}

    record Any(List<SegmentDsl> of) implements SegmentDsl {}

    record Not(SegmentDsl of) implements SegmentDsl {}

    record Predicate(String field, String op, Object value) implements SegmentDsl {}

    static SegmentDsl parse(Object json) {
        var count = new int[1];
        var tree = parse(json, 1, count);
        if (count[0] == 0) throw Problems.badRequest("a segment needs at least one condition");
        return tree;
    }

    private static SegmentDsl parse(Object json, int depth, int[] count) {
        if (depth > MAX_DEPTH) throw Problems.badRequest("segments nest at most " + MAX_DEPTH + " levels");
        if (!(json instanceof Map<?, ?> node)) throw Problems.badRequest("each condition is a JSON object");
        var keys = node.keySet();
        if (keys.equals(Set.of("all")) || keys.equals(Set.of("any"))) {
            var key = keys.iterator().next();
            if (!(node.get(key) instanceof List<?> items) || items.isEmpty()) {
                throw Problems.badRequest("\"" + key + "\" takes a non-empty list");
            }
            var children = new ArrayList<SegmentDsl>(items.size());
            for (var item : items) children.add(parse(item, depth + 1, count));
            return key.equals("all") ? new All(List.copyOf(children)) : new Any(List.copyOf(children));
        }
        if (keys.equals(Set.of("not"))) return new Not(parse(node.get("not"), depth + 1, count));
        if (keys.equals(Set.of("field", "op", "value"))) {
            if (++count[0] > MAX_PREDICATES) throw Problems.badRequest("a segment has at most " + MAX_PREDICATES + " conditions");
            if (!(node.get("field") instanceof String field) || !(node.get("op") instanceof String op)) {
                throw Problems.badRequest("field and op are strings");
            }
            return new Predicate(field, op, node.get("value"));
        }
        throw Problems.badRequest("a condition is {all}, {any}, {not} or {field, op, value}");
    }
}
