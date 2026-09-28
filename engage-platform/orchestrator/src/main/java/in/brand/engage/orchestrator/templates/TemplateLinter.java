package in.brand.engage.orchestrator.templates;

import static in.brand.engage.core.messaging.Category.AUTHENTICATION;
import static in.brand.engage.core.messaging.Category.UTILITY;

import in.brand.engage.core.messaging.Channel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The template lint. Every rule is an error: a template that fails does not
 * load, so the build (TemplateRegistryTest) and the worker's startup fail.
 *
 * <ul>
 *   <li>A utility or authentication template containing promotional language
 *       ({@code templates/lint/banned_words.txt}), in any locale.</li>
 *   <li>Push: a title over 40 or a body over 90 characters, rendered with the
 *       template's longest sample values.</li>
 *   <li>A variable used but not declared, or declared but not used. Push
 *       {@code url} and {@code image} are used by the payload, not the copy;
 *       push must declare {@code url}.</li>
 *   <li>No {@code en} locale; a copy variable with no sample value.</li>
 * </ul>
 */
public final class TemplateLinter {

    static final String BANNED_WORDS = "templates/lint/banned_words.txt";

    private final List<Pattern> banned;

    public TemplateLinter(List<Pattern> banned) {
        this.banned = List.copyOf(banned);
    }

    /** The lint with the repository's banned-words list. */
    public static TemplateLinter standard() {
        try (InputStream in = TemplateLinter.class.getClassLoader().getResourceAsStream(BANNED_WORDS)) {
            if (in == null) throw new IllegalStateException("missing " + BANNED_WORDS);
            return new TemplateLinter(parseBanned(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Pattern> parseBanned(String text) {
        return text.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> Pattern.compile(l, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
    }

    /** @return every problem, each prefixed with the template's source; empty = clean */
    public List<String> lint(MessageTemplate t) {
        var problems = new ArrayList<String>();
        var where = t.source() + " (" + t.key() + "): ";

        if (!t.locales().containsKey(MessageTemplate.FALLBACK_LOCALE)) {
            problems.add(where + "no 'en' locale; en is required and is the fallback");
        }

        var declared = new LinkedHashSet<>(t.vars());
        var used = new LinkedHashSet<String>();
        t.locales().values().forEach(c -> {
            used.addAll(Renderer.placeholders(c.title()));
            used.addAll(Renderer.placeholders(c.body()));
        });
        var payload = t.channel() == Channel.PUSH ? Renderer.PUSH_PAYLOAD_VARS : Set.<String>of();
        for (var v : used) {
            if (!declared.contains(v)) problems.add(where + "variable '" + v + "' is used but not declared in vars");
            else if (!t.samples().containsKey(v)) problems.add(where + "variable '" + v + "' has no sample value");
        }
        for (var v : declared) {
            if (!used.contains(v) && !payload.contains(v)) problems.add(where + "variable '" + v + "' is declared but not used");
        }
        if (t.channel() == Channel.PUSH && !declared.contains("url")) {
            problems.add(where + "push templates must declare 'url' (the click-through)");
        }

        if (t.category() == UTILITY || t.category() == AUTHENTICATION) {
            for (var e : t.locales().entrySet()) {
                for (var text : List.of(e.getValue().title(), e.getValue().body())) {
                    for (var p : banned) {
                        var m = p.matcher(text);
                        if (m.find()) {
                            problems.add(where + t.category().dbName() + " template contains promotional language '"
                                    + m.group() + "' in locale " + e.getKey() + " (rule " + p.pattern()
                                    + "). Meta re-classifies or rejects it; move the offer to a marketing template.");
                        }
                    }
                }
            }
        }

        if (t.channel() == Channel.PUSH && used.stream().allMatch(t.samples()::containsKey)) {
            for (var e : t.locales().entrySet()) {
                var title = Renderer.fill(e.getValue().title(), t.samples(), t.key());
                var body = Renderer.fill(e.getValue().body(), t.samples(), t.key());
                if (Renderer.length(title) > Renderer.PUSH_TITLE_MAX) {
                    problems.add(where + "title in " + e.getKey() + " is " + Renderer.length(title) + " characters with "
                            + "the sample values (max " + Renderer.PUSH_TITLE_MAX + "): \"" + title + "\"");
                }
                if (Renderer.length(body) > Renderer.PUSH_BODY_MAX) {
                    problems.add(where + "body in " + e.getKey() + " is " + Renderer.length(body) + " characters with "
                            + "the sample values (max " + Renderer.PUSH_BODY_MAX + "): \"" + body + "\"");
                }
            }
        }
        return problems;
    }

    /** Lints each template, plus duplicate keys across them. */
    public List<String> lintAll(List<MessageTemplate> templates) {
        var problems = new ArrayList<String>();
        var keys = new HashSet<String>();
        for (var t : templates) {
            if (!keys.add(t.key())) problems.add(t.source() + ": duplicate template key " + t.key());
            problems.addAll(lint(t));
        }
        return problems;
    }
}
