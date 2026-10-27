package in.brand.engage.orchestrator.templates;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.policy.TemplateCooldowns;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every message template, loaded from {@code templates/} on the classpath and
 * linted on load: one lint problem and the registry refuses to start, so a bad
 * template fails the worker at boot rather than a send at 9am.
 *
 * <p>On startup it upserts key, channel and category into the {@code templates}
 * table, which the policy engine reads. It never touches {@code status}: a
 * pause set by an operator (or the P4 quality sync) survives a deploy.
 *
 * <p>It is also where policy gets per-template cooldowns ({@link TemplateCooldowns}).
 */
@Singleton
public class TemplateRegistry implements TemplateCooldowns, ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateRegistry.class);
    static final String INDEX = "templates/index.txt";
    private static final Set<String> FIELDS = Set.of("key", "channel", "category", "cooldown", "locales", "vars", "samples",
            "buttons", "dlt_template_id", "msg91_template_id", "truncate", "header_image", "fallback");
    private static final Set<String> SMS_ONLY = Set.of("dlt_template_id", "msg91_template_id", "truncate");
    private static final Set<String> WHATSAPP_ONLY = Set.of("header_image", "fallback");
    private static final Set<String> COPY_FIELDS = Set.of("title", "body", "language", "provider_name");

    private final Map<String, MessageTemplate> byKey;
    private final Db db;

    @Inject
    public TemplateRegistry(Db db) {
        this(db, loadClasspath(), TemplateLinter.standard());
    }

    TemplateRegistry(Db db, List<MessageTemplate> templates, TemplateLinter linter) {
        var problems = linter.lintAll(templates);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("template lint failed:\n  " + String.join("\n  ", problems));
        }
        var map = new LinkedHashMap<String, MessageTemplate>();
        templates.forEach(t -> map.put(t.key(), t));
        this.byKey = Map.copyOf(map);
        this.db = db;
    }

    public Optional<MessageTemplate> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public List<MessageTemplate> all() {
        return List.copyOf(byKey.values());
    }

    @Override
    public Optional<Duration> cooldown(String templateKey) {
        return find(templateKey).map(MessageTemplate::cooldown);
    }

    /** The template, its fallback, and every template that falls back to it. */
    @Override
    public Set<String> cooldownKeys(String templateKey) {
        var keys = new java.util.HashSet<String>();
        keys.add(templateKey);
        find(templateKey).map(MessageTemplate::fallback).ifPresent(keys::add);
        for (var t : byKey.values()) if (templateKey.equals(t.fallback())) keys.add(t.key());
        return Set.copyOf(keys);
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        int changed = sync();
        LOG.info("templates: {} loaded, {} rows inserted or changed", byKey.size(), changed);
    }

    /** Upserts every template into {@code templates}. @return rows inserted or changed */
    public int sync() {
        return db.inTx(c -> {
            int n = 0;
            for (var t : byKey.values()) {
                n += Sql.update(c, """
                        INSERT INTO templates (key, channel, category, vars, header_image, title, body)
                        VALUES (?, CAST(? AS channel), CAST(? AS msg_category), ?, ?, ?, ?)
                        ON CONFLICT (key) DO UPDATE
                           SET channel = EXCLUDED.channel, category = EXCLUDED.category, vars = EXCLUDED.vars,
                               header_image = EXCLUDED.header_image, title = EXCLUDED.title, body = EXCLUDED.body,
                               updated_at = now()
                         WHERE (templates.channel, templates.category, templates.vars, templates.header_image,
                                templates.title, templates.body)
                               IS DISTINCT FROM (EXCLUDED.channel, EXCLUDED.category, EXCLUDED.vars, EXCLUDED.header_image,
                                EXCLUDED.title, EXCLUDED.body)""",
                        t.key(), t.channel().dbName(), t.category().dbName(), t.vars().toArray(String[]::new),
                        t.headerImage() != null, copy(t) == null ? null : blankToNull(copy(t).title()),
                        copy(t) == null ? null : copy(t).body());
            }
            return n;
        });
    }

    /* ------------------------------ loading ------------------------------ */

    static List<MessageTemplate> loadClasspath() {
        var index = resource(INDEX);
        var templates = new ArrayList<MessageTemplate>();
        for (var path : index.lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            templates.add(parse(resource("templates/" + path), "templates/" + path));
        }
        return templates;
    }

    /** Parses one YAML template. Structural errors throw; content rules are the lint's job. */
    static MessageTemplate parse(String yaml, String source) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(doc instanceof Map<?, ?> m)) throw invalid(source, "not a YAML mapping");
        for (var k : m.keySet()) {
            if (!FIELDS.contains(String.valueOf(k))) throw invalid(source, "unknown field '" + k + "'");
        }
        var key = text(m, "key", source);
        var channel = Channel.fromDb(text(m, "channel", source));
        var category = Category.fromDb(text(m, "category", source));
        boolean sms = channel == Channel.SMS;
        for (var f : SMS_ONLY) {
            if (!sms && m.containsKey(f)) throw invalid(source, "'" + f + "' is for SMS templates only");
        }
        for (var f : WHATSAPP_ONLY) {
            if (channel != Channel.WHATSAPP && m.containsKey(f)) throw invalid(source, "'" + f + "' is for WhatsApp templates only");
        }
        Duration cooldown = null;
        if (m.get("cooldown") != null) {
            try {
                cooldown = Duration.parse(String.valueOf(m.get("cooldown")));
            } catch (DateTimeParseException e) {
                throw invalid(source, "cooldown is not an ISO-8601 duration (PT6H, P1D): " + m.get("cooldown"));
            }
        }

        var locales = new LinkedHashMap<String, MessageTemplate.Copy>();
        if (!(m.get("locales") instanceof Map<?, ?> ls) || ls.isEmpty()) throw invalid(source, "locales is missing");
        boolean whatsapp = channel == Channel.WHATSAPP;
        for (var e : ls.entrySet()) {
            var where = source + " " + e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> copy)) throw invalid(source, "locale " + e.getKey() + " is not a mapping");
            for (var f : copy.keySet()) {
                if (!COPY_FIELDS.contains(String.valueOf(f))) throw invalid(where, "unknown field '" + f + "'");
            }
            // WhatsApp and SMS copy has no title; push copy has no Meta name or language.
            var title = whatsapp || sms ? "" : text(copy, "title", where);
            var language = whatsapp ? optional(copy, "language", "en") : null;
            var providerName = whatsapp ? optional(copy, "provider_name",
                    // en → the key; hi-Latn → key_hi (Meta names: lowercase, digits, _)
                    e.getKey().equals(MessageTemplate.FALLBACK_LOCALE) ? key
                            : key + "_" + String.valueOf(e.getKey()).split("-")[0].toLowerCase(java.util.Locale.ROOT)) : null;
            locales.put(String.valueOf(e.getKey()), new MessageTemplate.Copy(title, text(copy, "body", where), language, providerName));
        }

        var buttons = new ArrayList<MessageTemplate.Button>();
        if (m.get("buttons") instanceof List<?> bs) {
            for (var b : bs) {
                if (!(b instanceof Map<?, ?> bm)) throw invalid(source, "each button is {text, url}, {text, reply} or {copy_code}");
                if (bm.get("copy_code") != null) {
                    if (bm.size() != 1) throw invalid(source, "a copy_code button has no other fields (Meta sets its label)");
                    buttons.add(MessageTemplate.Button.copyCode(text(bm, "copy_code", source + " button").strip()));
                    continue;
                }
                var label = text(bm, "text", source + " button");
                if (bm.get("reply") != null) {
                    if (bm.get("url") != null) throw invalid(source, "button '" + label + "' has both url and reply");
                    buttons.add(MessageTemplate.Button.quickReply(label, text(bm, "reply", source + " button")));
                } else {
                    buttons.add(new MessageTemplate.Button(label, text(bm, "url", source + " button")));
                }
            }
        } else if (m.get("buttons") != null) {
            throw invalid(source, "buttons must be a list");
        }

        var vars = new ArrayList<String>();
        if (m.get("vars") instanceof List<?> vs) vs.forEach(v -> vars.add(String.valueOf(v)));
        else if (m.get("vars") != null) throw invalid(source, "vars must be a list");

        var samples = new LinkedHashMap<String, String>();
        if (m.get("samples") instanceof Map<?, ?> ss) ss.forEach((k, v) -> samples.put(String.valueOf(k), String.valueOf(v)));
        else if (m.get("samples") != null) throw invalid(source, "samples must be a mapping");

        MessageTemplate.Sms dlt = null;
        if (sms) {
            // Required, so a template DLT has not seen yet must say PENDING rather than leave it out.
            var truncate = new java.util.LinkedHashSet<String>();
            if (m.get("truncate") instanceof List<?> ts) ts.forEach(t -> truncate.add(String.valueOf(t)));
            else if (m.get("truncate") != null) throw invalid(source, "truncate must be a list");
            dlt = new MessageTemplate.Sms(text(m, "dlt_template_id", source).strip(),
                    text(m, "msg91_template_id", source).strip(), Set.copyOf(truncate));
        }

        var headerImage = m.get("header_image") == null ? null : text(m, "header_image", source).strip();
        var fallback = m.get("fallback") == null ? null : text(m, "fallback", source).strip();
        return new MessageTemplate(key, channel, category, cooldown, Map.copyOf(locales), List.copyOf(vars),
                Map.copyOf(samples), List.copyOf(buttons), source, dlt, headerImage, fallback);
    }

    private static String optional(Map<?, ?> m, String field, String fallback) {
        var v = m.get(field);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private static String text(Map<?, ?> m, String field, String source) {
        var v = m.get(field);
        if (v == null || String.valueOf(v).isBlank()) throw invalid(source, "'" + field + "' is missing");
        return String.valueOf(v);
    }

    private static IllegalStateException invalid(String source, String why) {
        return new IllegalStateException(source + ": " + why);
    }

    private static String resource(String path) {
        try (InputStream in = TemplateRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The English copy (the console's Message log shows it), or the first locale. */
    private static MessageTemplate.Copy copy(MessageTemplate t) {
        var en = t.locales().get(MessageTemplate.FALLBACK_LOCALE);
        return en != null ? en : t.locales().values().stream().findFirst().orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
