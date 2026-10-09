package in.brand.engage.admin.segments;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.web.Problems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SegmentCompilerTest {

    static Map<String, Object> p(String field, String op, Object value) {
        return Map.of("field", field, "op", op, "value", value);
    }

    static Predicates.Fragment compile(Object definition) {
        return SegmentCompiler.count(SegmentDsl.parse(definition));
    }

    static Problems.ApiException refused(Object definition) {
        return assertThrows(Problems.ApiException.class, () -> compile(definition));
    }

    @Test void a_value_is_bound_never_interpolated() {
        var evil = "KL'); DROP TABLE identities; --";
        var q = compile(Map.of("all", List.of(p("state", "in", List.of(evil)))));
        assertFalse(q.sql().contains("DROP"), q.sql());
        assertFalse(q.sql().contains("KL"), q.sql());
        assertArrayEquals(new String[] {evil.toUpperCase()}, (String[]) q.params().getFirst());
    }

    @Test void field_names_and_operators_come_only_from_the_whitelist() {
        assertTrue(refused(p("state; DROP TABLE identities", "in", List.of("KL"))).getMessage().contains("unknown"));
        assertTrue(refused(p("orders_count", ">= 0 OR 1=1 --", 1)).getMessage().contains("takes"));
        assertTrue(refused(p("bought_collection", "in", List.of("kurtas"))).getMessage().contains("not available yet"));
    }

    @Test void the_phase_6_example_compiles_to_one_condition_per_predicate() {
        var q = compile(Map.of("all", List.of(
                p("bought_product_type", "in", List.of("Kurta", "ethnic set")),
                p("last_order_at", "older_than", "P60D"),
                p("state", "in", List.of("kl", "TN", "KA")))));
        assertTrue(q.sql().startsWith("SELECT count(*) FROM identities i WHERE i.merged_into IS NULL AND ("), q.sql());
        assertEquals(3, q.params().size());
        assertArrayEquals(new String[] {"kurta", "ethnic set"}, (String[]) q.params().get(0));
        assertArrayEquals(new String[] {"KL", "TN", "KA"}, (String[]) q.params().get(2));
    }

    @Test void not_and_any_combine_without_new_sql_from_input() {
        var q = compile(Map.of("any", List.of(
                p("has_open_cart", "is", true),
                Map.of("not", p("wa_marketing", "is", true)))));
        assertTrue(q.sql().contains(" OR NOT "), q.sql());
        assertEquals(List.of(), q.params());
    }

    @Test void depth_and_size_are_limited() {
        Object deep = p("state", "in", List.of("KL"));
        for (int i = 0; i < 5; i++) deep = Map.of("all", List.of(deep));
        assertTrue(refused(deep).getMessage().contains("nest"));

        var many = new ArrayList<Object>();
        for (int i = 0; i < 31; i++) many.add(p("orders_count", "gte", 1));
        assertTrue(refused(Map.of("all", many)).getMessage().contains("at most 30"));
    }

    @Test void values_are_typed() {
        refused(p("orders_count", "gte", "1"));
        refused(p("orders_count", "gte", -1));
        refused(p("last_order_at", "older_than", "60 days"));
        refused(p("last_order_at", "older_than", "P-60D"));
        refused(p("state", "in", List.of()));
        refused(p("wa_capable", "is", "MAYBE"));
        refused(p("cart_value", "gte", 99.999));
        refused(Map.of("all", List.of()));
        refused(Map.of("all", List.of(p("state", "in", List.of("KL"))), "any", List.of()));
    }
}
