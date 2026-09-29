package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The lint, without a database. One test per rule. */
class TemplateLinterTest {

    final TemplateLinter lint = TemplateLinter.standard();

    @Test void every_template_in_the_repository_lints_clean() {
        var templates = TemplateRegistry.loadClasspath();
        assertEquals(5, templates.size(), "templates/push/*.yaml");
        assertEquals(List.of(), lint.lintAll(templates));
    }

    @Test void a_utility_template_saying_flat_200_off_fails() throws IOException {
        var t = TemplateRegistry.parse(fixture("fixtures/utility_with_offer.yaml"), "utility_with_offer.yaml");

        var problems = lint.lint(t);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("promotional language 'Flat ₹2'"), problems.getFirst());
    }

    @Test void a_template_that_fails_the_lint_stops_the_registry_from_loading() throws IOException {
        var bad = TemplateRegistry.parse(fixture("fixtures/utility_with_offer.yaml"), "utility_with_offer.yaml");

        var e = assertThrows(IllegalStateException.class, () -> new TemplateRegistry(null, List.of(bad), lint));
        assertTrue(e.getMessage().startsWith("template lint failed"), e.getMessage());
    }

    @Test void the_same_copy_marked_marketing_passes() {
        var t = TemplateRegistry.parse("""
                key: push_offer_v1
                channel: push
                category: marketing
                locales:
                  en: { title: "Order {{order}} shipped", body: "Flat ₹200 off your next order!" }
                vars: [order, url]
                samples: { order: "#10421" }
                """, "inline");
        assertEquals(List.of(), lint.lint(t));
    }

    @Test void banned_words_are_caught_in_every_locale_not_only_en() {
        var t = template("utility", "{ title: \"Back\", body: \"Size {{size}} is back.\" }",
                "{ title: \"Wapas\", body: \"Sale mein size {{size}} wapas.\" }", "[size, url]", "{ size: \"XXL\" }");
        var problems = lint.lint(t);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("hi-Latn"), problems.getFirst());
    }

    @Test void a_push_title_over_40_characters_with_the_samples_fails() {
        var t = template("marketing", "{ title: \"Size {{size}} is back in stock: {{product}}\", body: \"Short.\" }",
                null, "[size, product, url]", "{ size: \"XXL\", product: \"Floral Anarkali Kurta\" }");
        var problems = lint.lint(t);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("title in en is 48 characters"), problems.getFirst());
    }

    @Test void a_push_body_over_90_characters_with_the_samples_fails() {
        var body = "{{product}} " + "x".repeat(70);
        var t = template("marketing", "{ title: \"Hi\", body: \"" + body + "\" }", null,
                "[product, url]", "{ product: \"Floral Anarkali Kurta\" }");
        var problems = lint.lint(t);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("body in en is 92 characters"), problems.getFirst());
    }

    @Test void length_counts_characters_not_utf16_units() {
        // 40 rupee signs and emoji would be 40+ UTF-16 units but must count as 40 characters.
        var title = "₹".repeat(20) + "👗".repeat(20);    // 👗
        var t = template("marketing", "{ title: \"" + title + "\", body: \"b\" }", null, "[url]", "{}");
        assertEquals(List.of(), lint.lint(t));
    }

    @Test void a_variable_used_but_not_declared_fails() {
        var t = template("marketing", "{ title: \"Hi {{name}}\", body: \"b\" }", null, "[url]", "{ name: \"Priya\" }");
        assertEquals(List.of("inline (push_test_v1): variable 'name' is used but not declared in vars"), lint.lint(t));
    }

    @Test void a_variable_declared_but_not_used_fails_except_the_push_payload_ones() {
        var t = template("marketing", "{ title: \"Hi\", body: \"b\" }", null, "[size, url, image]", "{}");
        assertEquals(List.of("inline (push_test_v1): variable 'size' is declared but not used"), lint.lint(t));
    }

    @Test void a_push_template_without_url_fails() {
        var t = template("marketing", "{ title: \"Hi\", body: \"b\" }", null, "[]", "{}");
        assertEquals(List.of("inline (push_test_v1): push templates must declare 'url' (the click-through)"),
                lint.lint(t));
    }

    @Test void a_copy_variable_without_a_sample_fails() {
        var t = template("marketing", "{ title: \"Hi {{name}}\", body: \"b\" }", null, "[name, url]", "{}");
        assertEquals(List.of("inline (push_test_v1): variable 'name' has no sample value"), lint.lint(t));
    }

    @Test void a_template_without_an_en_locale_fails() {
        var t = TemplateRegistry.parse("""
                key: push_test_v1
                channel: push
                category: marketing
                locales:
                  hi-Latn: { title: "Namaste", body: "b" }
                vars: [url]
                """, "inline");
        assertEquals(List.of("inline (push_test_v1): no 'en' locale; en is required and is the fallback"),
                lint.lint(t));
    }

    @Test void duplicate_keys_across_files_fail() {
        var t = template("marketing", "{ title: \"Hi\", body: \"b\" }", null, "[url]", "{}");
        assertEquals(List.of("inline: duplicate template key push_test_v1"), lint.lintAll(List.of(t, t)));
    }

    @Test void an_unknown_field_is_a_typo_and_fails_to_parse() {
        var e = assertThrows(IllegalStateException.class, () -> TemplateRegistry.parse("""
                key: push_test_v1
                channel: push
                category: marketing
                cooldwon: PT6H
                locales: { en: { title: "t", body: "b" } }
                """, "inline"));
        assertEquals("inline: unknown field 'cooldwon'", e.getMessage());
    }

    static MessageTemplate template(String category, String en, String hi, String vars, String samples) {
        return TemplateRegistry.parse("""
                key: push_test_v1
                channel: push
                category: %s
                locales:
                  en: %s
                %s
                vars: %s
                samples: %s
                """.formatted(category, en, hi == null ? "" : "  hi-Latn: " + hi, vars, samples), "inline");
    }

    private static String fixture(String path) throws IOException {
        try (var in = Objects.requireNonNull(TemplateLinterTest.class.getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
