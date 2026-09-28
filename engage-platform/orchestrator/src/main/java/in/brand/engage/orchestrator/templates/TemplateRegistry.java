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
    private static final Set<String> FIELDS = Set.of("key", "channel", "category", "cooldown", "locales", "vars", "samples");

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
                        INSERT INTO templates (key, channel, category)
                        VALUES (?, CAST(? AS channel), CAST(? AS msg_category))
                        ON CONFLICT (key) DO UPDATE
                           SET channel = EXCLUDED.channel, category = EXCLUDED.category, updated_at = now()
                         WHERE (templates.channel, templates.category)
                               IS DISTINCT FROM (EXCLUDED.channel, EXCLUDED.category)""",
                        t.key(), t.channel().dbName(), t.category().dbName());
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
        for (var e : ls.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> copy)) throw invalid(source, "locale " + e.getKey() + " is not {title, body}");
            locales.put(String.valueOf(e.getKey()), new MessageTemplate.Copy(
                    text(copy, "title", source + " " + e.getKey()), text(copy, "body", source + " " + e.getKey())));
        }

        var vars = new ArrayList<String>();
        if (m.get("vars") instanceof List<?> vs) vs.forEach(v -> vars.add(String.valueOf(v)));
        else if (m.get("vars") != null) throw invalid(source, "vars must be a list");

        var samples = new LinkedHashMap<String, String>();
        if (m.get("samples") instanceof Map<?, ?> ss) ss.forEach((k, v) -> samples.put(String.valueOf(k), String.valueOf(v)));
        else if (m.get("samples") != null) throw invalid(source, "samples must be a mapping");

        return new MessageTemplate(key, channel, category, cooldown, Map.copyOf(locales), List.copyOf(vars),
                Map.copyOf(samples), source);
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
}
