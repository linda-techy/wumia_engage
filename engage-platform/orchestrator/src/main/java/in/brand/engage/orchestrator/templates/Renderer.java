package in.brand.engage.orchestrator.templates;

import in.brand.engage.core.messaging.Channel;
import jakarta.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a template's {@code {{var}}} placeholders. Push titles and bodies are
 * cut to the platform limits with an ellipsis: the lint proves the copy fits
 * with realistic values, and this keeps an unusually long product name from
 * being truncated mid-word by Android instead.
 */
@Singleton
public class Renderer {

    public static final int PUSH_TITLE_MAX = 40;
    public static final int PUSH_BODY_MAX = 90;

    /** Variables that go into the push payload rather than the copy (phase-3 §3). */
    public static final Set<String> PUSH_PAYLOAD_VARS = Set.of("url", "image");

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_][a-z0-9_]*)\\s*}}");

    public record Rendered(String locale, String title, String body, String url, String image) {}

    /**
     * @param locale preferred locale; falls back to {@code en} when the template lacks it
     * @throws IllegalArgumentException if a variable the copy uses is missing
     */
    public Rendered render(MessageTemplate template, String locale, Map<String, String> vars) {
        var chosen = locale != null && template.locales().containsKey(locale) ? locale : MessageTemplate.FALLBACK_LOCALE;
        var copy = template.locales().get(chosen);
        var title = fill(copy.title(), vars, template.key());
        var body = fill(copy.body(), vars, template.key());
        if (template.channel() == Channel.PUSH) {
            title = cut(title, PUSH_TITLE_MAX);
            body = cut(body, PUSH_BODY_MAX);
        }
        return new Rendered(chosen, title, body, vars.get("url"), vars.get("image"));
    }

    static String fill(String text, Map<String, String> vars, String templateKey) {
        var m = PLACEHOLDER.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            var value = vars.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("template " + templateKey + " needs variable '" + m.group(1) + "'");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Set<String> placeholders(String text) {
        var names = new LinkedHashSet<String>();
        var m = PLACEHOLDER.matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /** Length in characters as a person sees them (code points), not UTF-16 units. */
    static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String cut(String s, int max) {
        if (length(s) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
    }
}
