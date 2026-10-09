package in.brand.engage.admin.segments;

import in.brand.engage.admin.segments.Predicates.Fragment;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SegmentDsl} → one parameterised SQL condition on {@code i.id}. The
 * only text that reaches the SQL comes from {@link Predicates} and the fixed
 * {@code AND}/{@code OR}/{@code NOT} here; every value is a bind parameter.
 * Merged identities are always excluded: their survivor is the person.
 */
public final class SegmentCompiler {

    private SegmentCompiler() {}

    /** The condition, for {@code ... FROM identities i WHERE <sql>}. */
    public static Fragment where(SegmentDsl tree) {
        var params = new ArrayList<Object>();
        var sql = compile(tree, params);
        return new Fragment("i.merged_into IS NULL AND " + sql, List.copyOf(params));
    }

    public static Fragment count(SegmentDsl tree) {
        var w = where(tree);
        return new Fragment("SELECT count(*) FROM identities i WHERE " + w.sql(), w.params());
    }

    /** The identity ids, for materialising a campaign audience (P6-T06). */
    public static Fragment identities(SegmentDsl tree) {
        var w = where(tree);
        return new Fragment("SELECT i.id FROM identities i WHERE " + w.sql(), w.params());
    }

    private static String compile(SegmentDsl node, List<Object> params) {
        return switch (node) {
            case SegmentDsl.All all -> join(all.of(), " AND ", params);
            case SegmentDsl.Any any -> join(any.of(), " OR ", params);
            case SegmentDsl.Not not -> "NOT " + compile(not.of(), params);
            case SegmentDsl.Predicate p -> {
                var f = Predicates.compile(p.field(), p.op(), p.value());
                params.addAll(f.params());
                yield "(" + f.sql() + ")";
            }
        };
    }

    private static String join(List<SegmentDsl> children, String op, List<Object> params) {
        var parts = new ArrayList<String>(children.size());
        for (var child : children) parts.add(compile(child, params));
        return "(" + String.join(op, parts) + ")";
    }
}
