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
 *   <li>WhatsApp (Meta's own review rules): a body over 1,024 characters,
 *       starting or ending with a variable, or two variables side by side; a
 *       Meta name or language Meta would refuse; a URL button that is not
 *       https, whose variable is not at the very end, or whose label is over
 *       25 characters; buttons on a free-form (SERVICE) reply; the same Meta
 *       name and language twice.</li>
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
        t.buttons().forEach(b -> used.addAll(Renderer.placeholders(b.url())));
        if (t.channel() == Channel.WHATSAPP) problems.addAll(whatsapp(t, where));
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

    /** Lints each template, plus duplicate keys and duplicate WhatsApp names across them. */
    public List<String> lintAll(List<MessageTemplate> templates) {
        var problems = new ArrayList<String>();
        var keys = new HashSet<String>();
        var metaNames = new HashSet<String>();
        for (var t : templates) {
            if (!keys.add(t.key())) problems.add(t.source() + ": duplicate template key " + t.key());
            if (t.channel() == Channel.WHATSAPP && !t.freeForm()) {
                for (var c : t.locales().values()) {
                    if (!metaNames.add(c.providerName() + "|" + c.language())) {
                        problems.add(t.source() + ": WhatsApp name " + c.providerName() + " (" + c.language() + ") is used twice");
                    }
                }
            }
            problems.addAll(lint(t));
        }
        return problems;
    }

    static final java.util.regex.Pattern META_NAME = java.util.regex.Pattern.compile("[a-z0-9_]{1,512}");
    static final java.util.regex.Pattern META_LANGUAGE = java.util.regex.Pattern.compile("[a-z]{2,3}(_[A-Z]{2})?");
    static final java.util.regex.Pattern ADJACENT = java.util.regex.Pattern.compile("}}\\s*\\{\\{");
    static final int WA_BODY_MAX = 1024;
    static final int WA_BUTTON_TEXT_MAX = 25;

    private static List<String> whatsapp(MessageTemplate t, String where) {
        var problems = new ArrayList<String>();
        for (var e : t.locales().entrySet()) {
            var body = e.getValue().body().strip();
            var at = where + "locale " + e.getKey() + ": ";
            if (Renderer.length(body) > WA_BODY_MAX) problems.add(at + "body is over " + WA_BODY_MAX + " characters");
            if (body.startsWith("{{")) problems.add(at + "the body starts with a variable, which Meta rejects");
            if (body.endsWith("}}")) problems.add(at + "the body ends with a variable, which Meta rejects; end with text or punctuation");
            if (ADJACENT.matcher(body).find()) problems.add(at + "two variables side by side, which Meta rejects");
            if (!t.freeForm()) {
                if (e.getValue().providerName() == null || !META_NAME.matcher(e.getValue().providerName()).matches()) {
                    problems.add(at + "Meta name '" + e.getValue().providerName() + "' must be lowercase letters, digits and _");
                }
                if (e.getValue().language() == null || !META_LANGUAGE.matcher(e.getValue().language()).matches()) {
                    problems.add(at + "language '" + e.getValue().language() + "' is not a Meta language code (en, hi, en_US)");
                }
            }
        }
        if (t.freeForm() && !t.buttons().isEmpty()) problems.add(where + "a free-form (SERVICE) reply cannot have buttons");
        for (var b : t.buttons()) {
            if (!b.url().startsWith("https://")) problems.add(where + "button '" + b.text() + "' URL must be https");
            if (Renderer.length(b.text()) > WA_BUTTON_TEXT_MAX) problems.add(where + "button label '" + b.text() + "' is over 25 characters");
            var vars = Renderer.placeholders(b.url());
            if (vars.size() > 1 || (vars.size() == 1 && !b.url().endsWith("}}"))) {
                problems.add(where + "button '" + b.text() + "' may have one variable, at the very end of the URL");
            }
        }
        return problems;
    }
}
