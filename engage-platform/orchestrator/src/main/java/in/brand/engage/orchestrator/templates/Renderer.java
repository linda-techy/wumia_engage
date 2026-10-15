package in.brand.engage.orchestrator.templates;

import in.brand.engage.core.messaging.Channel;
import jakarta.inject.Singleton;
import in.brand.engage.channels.SmsCall;
import in.brand.engage.channels.WaTemplateCall;
import java.util.ArrayList;
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

    /** A WhatsApp template rendered for Meta: positional parameters, or a free-form body (SERVICE). */
    public record WhatsAppRendered(String locale, String body, WaTemplateCall call) {}

    /** An email rendered into the brand layout. */
    public record EmailRendered(String subject, String html, String text) {}

    /**
     * Email (P7-T02): the copy's title is the subject, its body the paragraphs,
     * the first button the call to action; {@link EmailLayout} adds the header,
     * the footer and the unsubscribe link.
     *
     * @throws IllegalArgumentException if a variable the copy or the button uses is missing
     */
    public EmailRendered email(MessageTemplate template, String locale, Map<String, String> vars, String unsubscribeUrl) {
        var chosen = locale != null && template.locales().containsKey(locale) ? locale : MessageTemplate.FALLBACK_LOCALE;
        var copy = template.locales().get(chosen);
        var subject = fill(copy.title(), vars, template.key());
        var body = fill(copy.body(), vars, template.key());
        var button = template.buttons().stream().filter(b -> !b.isQuickReply()).findFirst().orElse(null);
        var layout = EmailLayout.render(subject, body, button == null ? null : button.text(),
                button == null ? null : fill(button.url(), vars, template.key()), unsubscribeUrl);
        return new EmailRendered(subject, layout.html(), layout.text());
    }

    /** An SMS rendered for its DLT registration: the text as it will arrive, and the call. */
    public record SmsRendered(String body, SmsCall call) {}

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

    /**
     * WhatsApp: the body's variables become Meta's {{1}}, {{2}}… in order of
     * first appearance, and each URL button's trailing variable its suffix.
     * A free-form (SERVICE) template returns the filled body and no call.
     *
     * @throws IllegalArgumentException if a variable the copy or a button uses is missing
     */
    public WhatsAppRendered whatsapp(MessageTemplate template, String locale, Map<String, String> vars) {
        var chosen = locale != null && template.locales().containsKey(locale) ? locale : MessageTemplate.FALLBACK_LOCALE;
        var copy = template.locales().get(chosen);
        var body = fill(copy.body(), vars, template.key());
        if (template.freeForm()) return new WhatsAppRendered(chosen, body, null);
        var params = new ArrayList<String>();
        for (var name : placeholders(copy.body())) params.add(require(vars, name, template.key()));
        var suffixes = new ArrayList<String>();
        var replies = new ArrayList<String>();
        for (var b : template.buttons()) {
            if (b.isQuickReply()) {
                replies.add(b.reply());
                continue;
            }
            var name = buttonVariable(b.url());
            if (name != null) suffixes.add(require(vars, name, template.key()));
        }
        return new WhatsAppRendered(chosen, body,
                new WaTemplateCall(copy.providerName(), copy.language(), params, suffixes, replies));
    }

    /**
     * SMS: one value per {@code {{var}}}, in order, for the registered text
     * with {@code {#var#}} slots. A value over 30 characters is cut only if
     * the template declares it in {@code truncate}; otherwise it goes through
     * as it is and the adapter refuses the send (CLAUDE.md §5).
     *
     * @throws IllegalArgumentException a variable is missing, holds a link (links belong in the
     *                                  registered text, on a DLT-whitelisted domain), or is not GSM-7
     */
    public SmsRendered sms(MessageTemplate template, Map<String, String> vars) {
        var dlt = template.sms();
        if (dlt == null) throw new IllegalArgumentException("template " + template.key() + " has no DLT registration");
        var text = template.locales().get(MessageTemplate.FALLBACK_LOCALE).body();
        var names = new ArrayList<String>();
        var values = new ArrayList<String>();
        var m = PLACEHOLDER.matcher(text);
        var body = new StringBuilder();
        while (m.find()) {
            var name = m.group(1);
            var value = require(vars, name, template.key()).strip();
            var lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("://") || lower.contains("www.")) {
                throw new IllegalArgumentException("SMS variable '" + name + "' holds a link; links belong in the registered text");
            }
            if (!gsm7(value)) throw new IllegalArgumentException("SMS variable '" + name + "' has characters outside GSM-7");
            if (length(value) > SmsCall.VALUE_MAX && dlt.truncate().contains(name)) {
                value = value.substring(0, value.offsetByCodePoints(0, SmsCall.VALUE_MAX)).stripTrailing();
            }
            names.add(name);
            values.add(value);
            m.appendReplacement(body, Matcher.quoteReplacement(value));
        }
        m.appendTail(body);
        var registered = PLACEHOLDER.matcher(text).replaceAll(Matcher.quoteReplacement(SmsCall.SLOT));
        return new SmsRendered(body.toString(),
                new SmsCall(dlt.dltTemplateId(), dlt.providerTemplateId(), registered, names, values));
    }

    /** GSM 03.38: the basic set, then the extension set (two units each). */
    static final String GSM7_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    static final String GSM7_EXTENDED = "^{}\\[~]|€\f";

    /** True if every character is in the GSM-7 alphabet, so the SMS is not sent as Unicode. */
    static boolean gsm7(String s) {
        return s.chars().allMatch(c -> GSM7_BASIC.indexOf(c) >= 0 || GSM7_EXTENDED.indexOf(c) >= 0);
    }

    /** Length in GSM-7 units: extension characters take two. */
    static int gsm7Length(String s) {
        return s.chars().map(c -> GSM7_EXTENDED.indexOf(c) >= 0 ? 2 : 1).sum();
    }

    /** The body as submitted to Meta: {{order_no}} → {{1}}, by first appearance. */
    public static String metaBody(String body) {
        var index = new java.util.HashMap<String, Integer>();
        var m = PLACEHOLDER.matcher(body);
        var out = new StringBuilder();
        while (m.find()) {
            int n = index.computeIfAbsent(m.group(1), k -> index.size() + 1);
            m.appendReplacement(out, Matcher.quoteReplacement("{{" + n + "}}"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A URL button as submitted to Meta: the trailing {{var}} becomes {{1}}. */
    public static String metaUrl(String url) {
        return PLACEHOLDER.matcher(url).replaceAll("{{1}}");
    }

    /** The variable at the end of a button URL, or null for a static URL. */
    static String buttonVariable(String url) {
        var m = PLACEHOLDER.matcher(url);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static String require(Map<String, String> vars, String name, String templateKey) {
        var v = vars.get(name);
        if (v == null) throw new IllegalArgumentException("template " + templateKey + " needs variable '" + name + "'");
        return v;
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
