package in.brand.engage.worker;

import in.brand.engage.orchestrator.templates.WaManagementClient;
import in.brand.engage.orchestrator.templates.WaManagementClient.MetaTemplate;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.orchestrator.templates.MessageTemplate;
import in.brand.engage.orchestrator.templates.Renderer;
import in.brand.engage.orchestrator.templates.TemplateRegistry;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WhatsApp templates between our code and Meta (P4-T03).
 *
 * <ul>
 * <li>{@link #sync}: every 30 minutes (and on demand) mirror Meta's verdict
 *     per template locale into {@code wa_templates}. The category is the one
 *     Meta <b>approved</b>, which can differ from what we asked for: a
 *     utility template approved as marketing costs about seven times as
 *     much, so every mismatch is logged as an error. RED quality, or a
 *     PAUSED or DISABLED template, pauses our template at once. Phone
 *     numbers' quality and tier go to {@code wa_phone_numbers} with history.</li>
 * <li>{@link #submissions}: what would be submitted for review, built from
 *     the template files with {@link Renderer#metaBody} so the approved text
 *     is exactly what is sent. Submitting is a deliberate command, never a job.
 *     A photo template's sample photo (its header link with the sample
 *     values) is uploaded to Meta first; the template refers to it by handle.</li>
 * </ul>
 */
@Singleton
@Requires(beans = WaManagementClient.class)
public class WhatsAppTemplates {

    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppTemplates.class);
    private static final Set<String> STATUSES = Set.of("APPROVED", "PENDING", "REJECTED", "PAUSED", "DISABLED");

    private final WaManagementClient meta;
    private final TemplateRegistry registry;
    private final Db db;

    public WhatsAppTemplates(WaManagementClient meta, TemplateRegistry registry, Db db) {
        this.meta = meta;
        this.registry = registry;
        this.db = db;
    }

    public record Synced(int templates, int mismatches, int paused, int numbers) {}

    /** Our template locale for a Meta name and language. */
    private record Ours(MessageTemplate template, String locale) {}

    private Map<String, Ours> ours() {
        var map = new HashMap<String, Ours>();
        for (var t : registry.all()) {
            if (t.channel() != Channel.WHATSAPP || t.freeForm()) continue;
            t.locales().forEach((loc, copy) -> map.put(copy.providerName() + "|" + copy.language(), new Ours(t, loc)));
        }
        return map;
    }

    public Synced sync() {
        var templates = meta.templates();
        var numbers = meta.phoneNumbers();
        var ours = ours();
        int[] counts = new int[3];
        db.inTx(c -> {
            for (var m : templates) {
                var mine = ours.get(m.name() + "|" + m.language());
                var key = mine == null ? m.name() : mine.template().key();
                var status = STATUSES.contains(m.status()) ? m.status() : "PENDING";
                var category = m.category() == null ? null : m.category().toLowerCase(Locale.ROOT);
                if (!Set.of("marketing", "utility", "authentication").contains(String.valueOf(category))) category = null;
                var requested = mine == null ? category : mine.template().category().dbName();
                if (requested == null) continue;                                     // unknown category: nothing to map
                var approved = Set.of("APPROVED", "PAUSED", "DISABLED").contains(status) ? category : null;
                var quality = Set.of("GREEN", "YELLOW", "RED").contains(m.quality()) ? m.quality() : "UNKNOWN";
                Sql.update(c, """
                        INSERT INTO wa_templates (key, language, provider_name, requested_category, approved_category,
                                                  status, quality, rejected_reason, synced_at)
                        VALUES (?, ?, ?, CAST(? AS msg_category), CAST(? AS msg_category), ?, ?, ?, now())
                        ON CONFLICT (provider_name, language) DO UPDATE
                           SET key = EXCLUDED.key, requested_category = EXCLUDED.requested_category,
                               approved_category = EXCLUDED.approved_category, status = EXCLUDED.status,
                               quality = EXCLUDED.quality, rejected_reason = EXCLUDED.rejected_reason, synced_at = now()""",
                        key, m.language(), m.name(), requested, approved, status, quality,
                        m.rejectedReason() == null || m.rejectedReason().equals("NONE") ? null : m.rejectedReason());
                if (approved != null && !approved.equals(requested)) {
                    counts[1]++;
                    LOG.error("WhatsApp template {} ({}) was approved as {}, requested {}: reword it and resubmit",
                            m.name(), m.language(), approved, requested);
                }
                if (mine != null && (quality.equals("RED") || status.equals("PAUSED") || status.equals("DISABLED"))) {
                    counts[2] += Sql.update(c, """
                            UPDATE templates SET status = 'paused', updated_at = now() WHERE key = ? AND status = 'active'""", key);
                    LOG.error("WhatsApp template {} ({}) is {} / quality {}: paused", m.name(), m.language(), status, quality);
                }
                counts[0]++;
            }
            for (var n : numbers) number(c, n.id(), n.displayNumber(), n.verifiedName(), n.quality(), n.tier(), "sync", Instant.now());
            return null;
        });
        return new Synced(counts[0], counts[1], counts[2], numbers.size());
    }

    /** Upserts a number; writes history when quality or tier changed (the sync, or a webhook). */
    public static void number(java.sql.Connection c, String id, String display, String name, String quality, String tier,
                              String event, Instant at) throws java.sql.SQLException {
        var q = Set.of("GREEN", "YELLOW", "RED").contains(quality) ? quality : "UNKNOWN";
        int changed = Sql.update(c, """
                INSERT INTO wa_phone_numbers (phone_number_id, display_number, verified_name, quality, messaging_tier, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (phone_number_id) DO UPDATE
                   SET display_number = COALESCE(EXCLUDED.display_number, wa_phone_numbers.display_number),
                       verified_name = COALESCE(EXCLUDED.verified_name, wa_phone_numbers.verified_name),
                       quality = EXCLUDED.quality, messaging_tier = COALESCE(EXCLUDED.messaging_tier, wa_phone_numbers.messaging_tier),
                       updated_at = now()
                 WHERE (wa_phone_numbers.quality, wa_phone_numbers.messaging_tier)
                       IS DISTINCT FROM (EXCLUDED.quality, COALESCE(EXCLUDED.messaging_tier, wa_phone_numbers.messaging_tier))""",
                id, display == null ? id : display, name, q, tier);
        if (changed > 0) {
            Sql.update(c, """
                    INSERT INTO wa_phone_number_history (phone_number_id, quality, messaging_tier, event, observed_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", id, q, tier, event, at.atOffset(ZoneOffset.UTC));
        }
    }

    /** A template locale Meta does not have yet. */
    public record Submission(MessageTemplate template, MessageTemplate.Copy copy) {

        /** The sample header photo's link (the header with the sample values), or null without a header. */
        public String samplePhoto() {
            if (template.headerImage() == null) return null;
            var m = PLACEHOLDER.matcher(template.headerImage());
            var out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(template.samples().getOrDefault(m.group(1), "")));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /** Shown in a dry run, where nothing is uploaded. */
    static final String HANDLE_ON_SUBMIT = "<the sample photo, uploaded on --yes>";
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{\\s*([a-z_][a-z0-9_]*)\\s*}}");

    /** Every WhatsApp template locale not yet on Meta. */
    public List<Submission> submissions() {
        var existing = new java.util.HashSet<String>();
        for (var m : meta.templates()) existing.add(m.name() + "|" + m.language());
        var out = new ArrayList<Submission>();
        for (var t : registry.all()) {
            if (t.channel() != Channel.WHATSAPP || t.freeForm()) continue;
            for (var e : t.locales().entrySet()) {
                var copy = e.getValue();
                if (existing.contains(copy.providerName() + "|" + copy.language())) continue;
                out.add(new Submission(t, copy));
            }
        }
        return out;
    }

    static Map<String, Object> payload(MessageTemplate t, MessageTemplate.Copy copy) {
        return payload(t, copy, HANDLE_ON_SUBMIT);
    }

    /**
     * As the Graph API wants it. A photo template gets an IMAGE header whose
     * example is {@code headerHandle}, Meta's handle for the uploaded sample photo.
     */
    static Map<String, Object> payload(MessageTemplate t, MessageTemplate.Copy copy, String headerHandle) {
        var components = new ArrayList<Map<String, Object>>();
        if (t.headerImage() != null) {
            components.add(Map.of("type", "HEADER", "format", "IMAGE", "example", Map.of("header_handle", List.of(headerHandle))));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "BODY");
        body.put("text", Renderer.metaBody(copy.body()));
        var examples = new ArrayList<String>();
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*([a-z_][a-z0-9_]*)\\s*}}").matcher(copy.body());
        var seen = new java.util.LinkedHashSet<String>();
        while (m.find()) if (seen.add(m.group(1))) examples.add(t.samples().getOrDefault(m.group(1), m.group(1)));
        if (!examples.isEmpty()) body.put("example", Map.of("body_text", List.of(examples)));
        components.add(body);
        if (!t.buttons().isEmpty()) {
            var buttons = new ArrayList<Map<String, Object>>();
            for (var b : t.buttons()) {
                var button = new LinkedHashMap<String, Object>();
                if (b.isCopyCode()) {
                    var cm = java.util.regex.Pattern.compile("\\{\\{\\s*([a-z_][a-z0-9_]*)\\s*}}").matcher(b.copyCode());
                    button.put("type", "COPY_CODE");
                    button.put("example", cm.find() ? t.samples().getOrDefault(cm.group(1), "WUMIKA10") : "WUMIKA10");
                    buttons.add(button);
                    continue;
                }
                if (b.isQuickReply()) {
                    button.put("type", "QUICK_REPLY");
                    button.put("text", b.text());
                    buttons.add(button);
                    continue;
                }
                button.put("type", "URL");
                button.put("text", b.text());
                button.put("url", Renderer.metaUrl(b.url()));
                var bm = java.util.regex.Pattern.compile("\\{\\{\\s*([a-z_][a-z0-9_]*)\\s*}}").matcher(b.url());
                if (bm.find()) button.put("example", List.of(b.url().replace(bm.group(), t.samples().getOrDefault(bm.group(1), "1"))));
                buttons.add(button);
            }
            components.add(Map.of("type", "BUTTONS", "buttons", buttons));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("name", copy.providerName());
        out.put("language", copy.language());
        out.put("category", t.category().dbName().toUpperCase(Locale.ROOT));
        out.put("components", components);
        return out;
    }

    /** What a submission would send; a photo template's sample is not uploaded. */
    public Map<String, Object> preview(Submission s) {
        return payload(s.template(), s.copy());
    }

    /** Uploads a photo template's sample photo, then submits the template for review. */
    public Map<String, Object> submit(Submission s) {
        var handle = HANDLE_ON_SUBMIT;
        var sample = s.samplePhoto();
        if (sample != null) handle = meta.uploadSample(sample);
        return meta.create(payload(s.template(), s.copy(), handle));
    }
}
