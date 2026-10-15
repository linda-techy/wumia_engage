package in.brand.engage.orchestrator.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P7-T01/T02: email templates as code, rendered into the one brand layout. */
class EmailTemplateTest {

    static final String CART = """
            key: email_cart_v1
            channel: email
            category: marketing
            locales:
              en:
                title: "Still thinking about {{product}}?"
                body: "Hi {{name}},\\n\\n{{product}} is still in your bag.\\n\\nTake another look."
            buttons:
              - { text: "View your bag", url: "{{url}}" }
            vars: [name, product, url]
            samples: { name: "Priya", product: "Kundan Jhumka Earrings", url: "https://wumika.com/cart" }
            """;

    static MessageTemplate parse(String yaml) {
        return TemplateRegistry.parse(yaml, "test.yaml");
    }

    static List<String> lint(String yaml) {
        return TemplateLinter.standard().lint(parse(yaml));
    }

    @Test void it_renders_subject_paragraphs_button_and_unsubscribe() {
        var r = new Renderer().email(parse(CART), "en",
                Map.of("name", "Priya", "product", "Jhumkas <gold> & pearl", "url", "https://wumika.com/cart?utm_source=engage"),
                "https://engage.wumika.com/u/TOKEN");
        assertEquals("Still thinking about Jhumkas <gold> & pearl?", r.subject());
        assertTrue(r.html().contains("<p style=\"margin:0 0 16px;\">Hi Priya,</p>"), r.html());
        assertTrue(r.html().contains("Jhumkas &lt;gold&gt; &amp; pearl is still in your bag."), "every value is escaped");
        assertTrue(r.html().contains("href=\"https://wumika.com/cart?utm_source=engage\""));
        assertTrue(r.html().contains("href=\"https://engage.wumika.com/u/TOKEN\""), "the unsubscribe link is in the footer");
        assertTrue(r.text().contains("View your bag: https://wumika.com/cart?utm_source=engage"));
        assertTrue(r.text().endsWith("Unsubscribe: https://engage.wumika.com/u/TOKEN"));
    }

    @Test void the_repositorys_email_templates_lint_clean() {
        var emails = TemplateRegistry.loadClasspath().stream()
                .filter(t -> t.channel() == in.brand.engage.core.messaging.Channel.EMAIL).toList();
        assertEquals(List.of("email_cart_v1", "email_checkout_v1", "email_winback_v1"),
                emails.stream().map(MessageTemplate::key).sorted().toList());
        for (var t : emails) assertEquals(List.of(), TemplateLinter.standard().lint(t), t.key());
    }

    @Test void transactional_email_and_emails_without_one_button_are_refused() {
        assertTrue(lint(CART.replace("category: marketing", "category: utility")).stream()
                .anyMatch(p -> p.contains("marketing email only")));
        assertTrue(lint(CART.replace("buttons:\n  - { text: \"View your bag\", url: \"{{url}}\" }\n", "")
                .replace("vars: [name, product, url]", "vars: [name, product]")).stream()
                .anyMatch(p -> p.contains("exactly one URL button")));
        assertTrue(lint(CART.replace("Still thinking about {{product}}?", "x".repeat(71))).stream().anyMatch(p -> p.contains("subject")));
    }
}
