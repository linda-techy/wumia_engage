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
 *       name and language twice; a header photo that is not an https link,
 *       or with no fallback; a fallback that is missing, not the same
 *       category and cooldown, has a header itself, or needs a variable the
 *       photo template does not declare.</li>
 *   <li>SMS (DLT, CLAUDE.md §5): a marketing or service category; a DLT id
 *       that is neither 19 digits nor PENDING; a locale other than {@code en};
 *       a variable used twice; a sample value over 30 characters that is not
 *       declared in {@code truncate}; text outside the GSM-7 alphabet (it
 *       would go as Unicode, 70 characters a part); more than one 160-character
 *       part with the sample values; a link that is not https on a domain in
 *       {@code templates/lint/sms_url_domains.txt}.</li>
 * </ul>
 */
public final class TemplateLinter {

    static final String BANNED_WORDS = "templates/lint/banned_words.txt";
    static final String SMS_URL_DOMAINS = "templates/lint/sms_url_domains.txt";

    private final List<Pattern> banned;
    private final Set<String> smsDomains;

    public TemplateLinter(List<Pattern> banned) {
        this(banned, Set.of());
    }

    public TemplateLinter(List<Pattern> banned, Set<String> smsDomains) {
        this.banned = List.copyOf(banned);
        this.smsDomains = Set.copyOf(smsDomains);
    }

    /** The lint with the repository's banned-words and SMS link-domain lists. */
    public static TemplateLinter standard() {
        var domains = new HashSet<String>();
        resource(SMS_URL_DOMAINS).lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .forEach(l -> domains.add(l.toLowerCase(java.util.Locale.ROOT)));
        return new TemplateLinter(parseBanned(resource(BANNED_WORDS)), domains);
    }

    private static String resource(String path) {
        try (InputStream in = TemplateLinter.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
        t.buttons().stream().filter(b -> !b.isQuickReply()).forEach(b -> used.addAll(Renderer.placeholders(b.url())));
        if (t.headerImage() != null) used.addAll(Renderer.placeholders(t.headerImage()));
        if (t.channel() == Channel.WHATSAPP) problems.addAll(whatsapp(t, where));
        if (t.channel() == Channel.SMS) problems.addAll(sms(t, where));
        if (t.channel() == Channel.EMAIL) problems.addAll(email(t, where));
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
        var byKey = new java.util.HashMap<String, MessageTemplate>();
        templates.forEach(t -> byKey.put(t.key(), t));
        for (var t : templates) {
            if (t.fallback() != null) problems.addAll(fallback(t, byKey.get(t.fallback())));
        }
        return problems;
    }

    /**
     * A fallback goes out in the photo template's place, from the same
     * variables, so it must be a plain WhatsApp template of the same category
     * and cooldown that needs nothing the photo template does not have.
     */
    private static List<String> fallback(MessageTemplate t, MessageTemplate f) {
        var where = t.source() + " (" + t.key() + "): ";
        if (f == null) return List.of(where + "fallback " + t.fallback() + " is not a template");
        var problems = new ArrayList<String>();
        if (f.channel() != t.channel() || f.category() != t.category()) {
            problems.add(where + "fallback " + f.key() + " must be the same channel and category (" + t.category().dbName() + ")");
        }
        if (f.headerImage() != null || f.fallback() != null) {
            problems.add(where + "fallback " + f.key() + " must be a plain template, with no header and no fallback of its own");
        }
        if (!java.util.Objects.equals(f.cooldown(), t.cooldown())) {
            problems.add(where + "fallback " + f.key() + " must have the same cooldown; they count as one message");
        }
        for (var v : f.vars()) {
            if (!t.vars().contains(v)) problems.add(where + "fallback " + f.key() + " needs '" + v + "', which this template does not declare");
        }
        return problems;
    }

    static final int EMAIL_SUBJECT_MAX = 70;

    /**
     * Email (P7-T01/T02): marketing only (Shopify sends transactional email);
     * a subject of at most 70 characters with the samples; exactly one URL
     * button; the rendered HTML under {@link EmailLayout#MAX_HTML_BYTES}.
     */
    private static List<String> email(MessageTemplate t, String where) {
        var problems = new ArrayList<String>();
        if (t.category() != in.brand.engage.core.messaging.Category.MARKETING) {
            problems.add(where + "Engage sends marketing email only; Shopify sends transactional email");
        }
        var urlButtons = t.buttons().stream().filter(b -> !b.isQuickReply()).count();
        if (urlButtons != 1 || t.buttons().size() != 1) problems.add(where + "an email needs exactly one URL button (its call to action)");
        for (var e : t.locales().entrySet()) {
            var placeholdersKnown = Renderer.placeholders(e.getValue().title()).stream().allMatch(t.samples()::containsKey)
                    && Renderer.placeholders(e.getValue().body()).stream().allMatch(t.samples()::containsKey);
            if (!placeholdersKnown) continue;
            var subject = Renderer.fill(e.getValue().title(), t.samples(), t.key());
            if (Renderer.length(subject) > EMAIL_SUBJECT_MAX) {
                problems.add(where + "subject in " + e.getKey() + " is " + Renderer.length(subject) + " characters with the samples (max "
                        + EMAIL_SUBJECT_MAX + "): \"" + subject + "\"");
            }
            var html = EmailLayout.render(subject, Renderer.fill(e.getValue().body(), t.samples(), t.key()), "x",
                    "https://example.com/" + "x".repeat(200), "https://example.com/u/" + "x".repeat(44)).html();
            if (html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > EmailLayout.MAX_HTML_BYTES) {
                problems.add(where + "the email is over 100 KB; Gmail would clip it");
            }
        }
        return problems;
    }

    static final Pattern URL = Pattern.compile("(\\S+)://([^/\\s]+)");
    static final int SMS_PART = 160;

    private List<String> sms(MessageTemplate t, String where) {
        var problems = new ArrayList<String>();
        var dlt = t.sms();
        if (dlt == null) return List.of(where + "an SMS template needs dlt_template_id and msg91_template_id");
        if (t.category() != UTILITY && t.category() != AUTHENTICATION) {
            problems.add(where + "SMS is transactional only (CLAUDE.md §3.2): category " + t.category().dbName()
                    + " is not allowed; there are no promotional SMS templates");
        }
        if (!in.brand.engage.channels.SmsCall.DLT_ID.matcher(dlt.dltTemplateId()).matches() && !dlt.dltTemplateId().equals(MessageTemplate.Sms.PENDING)) {
            problems.add(where + "dlt_template_id '" + dlt.dltTemplateId() + "' is neither a 19-digit DLT id nor PENDING");
        }
        if (!t.locales().keySet().equals(Set.of(MessageTemplate.FALLBACK_LOCALE))) {
            problems.add(where + "SMS has one registered text: only the 'en' locale, got " + t.locales().keySet());
        }
        for (var v : dlt.truncate()) {
            if (!t.vars().contains(v)) problems.add(where + "truncate names '" + v + "', which is not in vars");
        }
        var copy = t.locales().get(MessageTemplate.FALLBACK_LOCALE);
        if (copy == null) return problems;
        var body = copy.body();
        var seen = new HashSet<String>();
        var m = Renderer.PLACEHOLDER.matcher(body);
        while (m.find()) {
            if (!seen.add(m.group(1))) problems.add(where + "variable '" + m.group(1) + "' is used twice; each DLT slot takes one");
        }
        if (!Renderer.gsm7(body)) problems.add(where + "the body has characters outside GSM-7, so it would go as Unicode");
        for (var v : seen) {
            var sample = t.samples().get(v);
            if (sample == null) continue;
            if (Renderer.length(sample) > in.brand.engage.channels.SmsCall.VALUE_MAX && !dlt.truncate().contains(v)) {
                problems.add(where + "sample for '" + v + "' is over 30 characters, the DLT limit for a variable; "
                        + "shorten it or declare it in truncate");
            }
        }
        if (seen.stream().allMatch(t.samples()::containsKey)) {
            var rendered = Renderer.fill(body, t.samples(), t.key());
            if (Renderer.gsm7Length(rendered) > SMS_PART) {
                problems.add(where + "the body is " + Renderer.gsm7Length(rendered) + " characters with the sample values; "
                        + "keep it to one 160-character part: \"" + rendered + "\"");
            }
        }
        var u = URL.matcher(body);
        while (u.find()) {
            var host = u.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!u.group(1).endsWith("https")) problems.add(where + "link " + u.group() + " must be https");
            if (host.contains("{{")) {
                problems.add(where + "link host is a variable; DLT needs the domain in the registered text");
            } else if (smsDomains.stream().noneMatch(d -> host.equals(d) || host.endsWith("." + d))) {
                problems.add(where + "link to " + host + ", which is not in " + SMS_URL_DOMAINS + " (whitelist it on DLT first)");
            }
        }
        return problems;
    }

    static final java.util.regex.Pattern META_NAME = java.util.regex.Pattern.compile("[a-z0-9_]{1,512}");
    static final java.util.regex.Pattern META_LANGUAGE = java.util.regex.Pattern.compile("[a-z]{2,3}(_[A-Z]{2})?");
    static final java.util.regex.Pattern QUICK_REPLY = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,63}");
    static final java.util.regex.Pattern ADJACENT = java.util.regex.Pattern.compile("}}\\s*\\{\\{");
    /** A variable with nothing but punctuation and spaces before it. */
    static final java.util.regex.Pattern LEADING_VAR = java.util.regex.Pattern.compile("^[^\\p{L}\\p{N}]*\\{\\{");
    /** A variable with nothing but punctuation and spaces after it. */
    static final java.util.regex.Pattern TRAILING_VAR = java.util.regex.Pattern.compile("}}[^\\p{L}\\p{N}]*$");
    static final int WA_BODY_MAX = 1024;
    static final int WA_BUTTON_TEXT_MAX = 25;

    private static List<String> whatsapp(MessageTemplate t, String where) {
        var problems = new ArrayList<String>();
        for (var e : t.locales().entrySet()) {
            var body = e.getValue().body().strip();
            var at = where + "locale " + e.getKey() + ": ";
            if (Renderer.length(body) > WA_BODY_MAX) problems.add(at + "body is over " + WA_BODY_MAX + " characters");
            // Meta (subcode 2388299) counts punctuation as nothing: "…for {{2}}." and "({{3}})." end with a variable.
            if (LEADING_VAR.matcher(body).find()) problems.add(at + "the body starts with a variable, which Meta rejects");
            if (TRAILING_VAR.matcher(body).find()) {
                problems.add(at + "the body ends with a variable (only punctuation after it), which Meta rejects; end with words");
            }
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
        if (t.headerImage() != null) {
            if (t.freeForm()) problems.add(where + "a free-form (SERVICE) reply cannot have a header photo");
            if (!t.headerImage().startsWith("https://")) problems.add(where + "header_image must be an https link");
            if (t.fallback() == null) {
                problems.add(where + "a template with header_image needs a fallback without one, for people whose product has no photo");
            }
        }
        if (t.fallback() != null && t.fallback().equals(t.key())) problems.add(where + "a template cannot be its own fallback");
        long replies = t.buttons().stream().filter(MessageTemplate.Button::isQuickReply).count();
        if (replies > 0 && replies < t.buttons().size()) {
            problems.add(where + "mix of quick-reply and URL buttons; use one kind per template");
        }
        if (replies > 3) problems.add(where + "more than 3 quick replies");
        var payloads = new HashSet<String>();
        for (var b : t.buttons()) {
            if (b.isQuickReply()) {
                if (Renderer.length(b.text()) > WA_BUTTON_TEXT_MAX) problems.add(where + "button label '" + b.text() + "' is over 25 characters");
                if (!QUICK_REPLY.matcher(b.reply()).matches()) {
                    problems.add(where + "quick reply '" + b.reply() + "' must be lowercase letters, digits and _ (it becomes an event name)");
                }
                if (!payloads.add(b.reply())) problems.add(where + "quick reply '" + b.reply() + "' is used twice");
                continue;
            }
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
