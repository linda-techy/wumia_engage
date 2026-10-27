package in.brand.engage.admin.messages;

import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.customers.CustomerQueries;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.privacy.Masks;
import in.brand.engage.persistence.Db;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Message log (V52): every send to every customer, newest first. Each
 * row shows who (name, masked phone and email; the customer page has the
 * rest), what (template, journey or campaign, and the words: WhatsApp's
 * actual text from the inbox, otherwise the template wording), and what
 * happened (sent, delivered, read, tapped; or blocked, held, failed and why).
 */
@Controller("/api/message-log")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MessageLogController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int PAGE = 50;

    /** The console's outcome filter, as send statuses. */
    static final Map<String, List<String>> OUTCOMES = Map.of(
            "sent", List.of("sent", "delivered", "read", "clicked"),
            "delivered", List.of("delivered", "read", "clicked"),
            "read", List.of("read", "clicked"),
            "clicked", List.of("clicked"),
            "blocked", List.of("blocked"),
            "deferred", List.of("deferred"),
            "failed", List.of("failed"),
            "queued", List.of("queued"));
    static final Set<String> CHANNELS = Set.of("push", "whatsapp", "sms", "email");

    private final Db db;
    private final CustomerQueries customers;

    public MessageLogController(Db db, CustomerQueries customers) {
        this.db = db;
        this.customers = customers;
    }

    /**
     * @param from   first IST day, yyyy-MM-dd (default: 7 days ago)
     * @param to     last IST day, inclusive (default: today)
     * @param q      a customer's exact email or phone
     * @param before the last id of the previous page
     */
    @RequiresRole(Role.VIEWER)
    @Get
    public Map<String, Object> list(@Nullable @QueryValue String channel, @Nullable @QueryValue String outcome,
                                    @Nullable @QueryValue String journey, @Nullable @QueryValue String from,
                                    @Nullable @QueryValue String to, @Nullable @QueryValue String q,
                                    @Nullable @QueryValue Long before) {
        if (channel != null && !channel.isBlank() && !CHANNELS.contains(channel)) throw Problems.badRequest("channel is push, whatsapp, sms or email");
        if (outcome != null && !outcome.isBlank() && !OUTCOMES.containsKey(outcome)) throw Problems.badRequest("unknown outcome " + outcome);
        var today = LocalDate.now(IST);
        var first = day(from, today.minusDays(6));
        var last = day(to, today);
        if (last.isBefore(first)) throw Problems.badRequest("the end date is before the start date");

        return db.inTx(c -> {
            UUID who = null;
            if (q != null && !q.isBlank()) {
                var found = customers.lookup(c, q.strip());
                if (found.isEmpty()) return Map.<String, Object>of("rows", List.of(), "counts", Map.of(), "customerNotFound", true);
                who = found.get();
            }
            var where = new StringBuilder(" WHERE s.created_at >= ? AND s.created_at < ?");
            var params = new ArrayList<Object>(List.of(first.atStartOfDay(IST).toOffsetDateTime(),
                    last.plusDays(1).atStartOfDay(IST).toOffsetDateTime()));
            if (channel != null && !channel.isBlank()) {
                where.append(" AND s.channel = CAST(? AS channel)");
                params.add(channel);
            }
            if (journey != null && !journey.isBlank()) {
                where.append(journey.equals("campaign") ? " AND s.intent_key LIKE 'campaign:%'" : " AND s.intent_key = ?");
                if (!journey.equals("campaign")) params.add(journey);
            }
            if (who != null) {
                where.append(" AND s.identity_id = ?");
                params.add(who);
            }
            // Counts per status over the filters, before the outcome filter and paging.
            var counts = new LinkedHashMap<String, Object>();
            for (var r : Rows.list(c, "SELECT s.status::text AS status, count(*) AS n FROM sends s" + where + " GROUP BY 1", params.toArray())) {
                counts.put((String) r.get("status"), r.get("n"));
            }
            if (outcome != null && !outcome.isBlank()) {
                where.append(" AND s.status::text = ANY(?)");
                params.add(OUTCOMES.get(outcome).toArray(String[]::new));
            }
            if (before != null) {
                where.append(" AND s.id < ?");
                params.add(before);
            }
            var rows = Rows.list(c, """
                    SELECT s.id, s.created_at, s.channel::text AS channel, s.category::text AS category, s.template_key,
                           s.intent_key, s.status::text AS status, s.failed_reason, s.decision->>'reason' AS block_reason,
                           s.sent_at, s.delivered_at, s.read_at, s.clicked_at, s.cost_paise, s.identity_id,
                           COALESCE(NULLIF(p.attrs->>'first_name', ''), NULLIF(p.attrs->>'wa_name', '')) AS name,
                           (SELECT k.value FROM identity_keys k WHERE k.identity_id = s.identity_id AND k.kind = 'phone' LIMIT 1) AS phone,
                           (SELECT k.value FROM identity_keys k WHERE k.identity_id = s.identity_id AND k.kind = 'email' LIMIT 1) AS email,
                           m.body AS text, t.title AS template_title, t.body AS template_body, cp.name AS campaign
                      FROM sends s
                      LEFT JOIN profiles p ON p.identity_id = s.identity_id
                      LEFT JOIN templates t ON t.key = s.template_key
                      LEFT JOIN LATERAL (SELECT body FROM wa_messages WHERE send_id = s.id ORDER BY id LIMIT 1) m ON true
                      LEFT JOIN campaigns cp ON s.intent_key LIKE 'campaign:%' AND cp.id::text = substr(s.intent_key, 10)
                    """ + where + " ORDER BY s.id DESC LIMIT " + (PAGE + 1), params.toArray());
            boolean more = rows.size() > PAGE;
            if (more) rows = new ArrayList<>(rows.subList(0, PAGE));
            for (var r : rows) {
                r.put("phone", r.get("phone") == null ? null : Msisdn.mask((String) r.get("phone")));
                r.put("email", r.get("email") == null ? null : Masks.email((String) r.get("email")));
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("rows", rows);
            out.put("counts", counts);
            out.put("more", more);
            out.put("from", first.toString());
            out.put("to", last.toString());
            return out;
        });
    }

    /** The journeys and campaigns that sent anything in the last 90 days, for the filter. */
    @RequiresRole(Role.VIEWER)
    @Get("/journeys")
    public List<String> journeys() {
        return db.inTx(c -> {
            var out = new ArrayList<String>();
            boolean campaigns = false;
            for (var r : Rows.list(c, """
                    SELECT DISTINCT intent_key FROM sends
                     WHERE intent_key IS NOT NULL AND created_at > now() - interval '90 days' ORDER BY 1""")) {
                var k = (String) r.get("intentKey");
                if (k.startsWith("campaign:")) campaigns = true;
                else out.add(k);
            }
            if (campaigns) out.addFirst("campaign");
            return out;
        });
    }

    private static LocalDate day(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw Problems.badRequest("dates are yyyy-MM-dd");
        }
    }
}
