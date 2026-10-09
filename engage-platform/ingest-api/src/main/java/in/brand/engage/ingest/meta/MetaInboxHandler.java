package in.brand.engage.ingest.meta;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.persistence.CapabilityService;
import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.DeliveryOutcome;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.IdentityResolver;
import in.brand.engage.persistence.IdentityResolver.Key;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WhatsApp Cloud API webhooks, out of the inbox (P4-T04; phase-4 §3).
 *
 * <p><b>Statuses</b> ({@code messages.statuses[]}), matched to {@code sends}
 * by the message id:
 * <ul>
 * <li>sent / delivered / read advance monotonically: a late {@code sent}
 *     never un-reads. Delivered and read make the number CAPABLE.</li>
 * <li>{@code failed} marks the send failed (unless it was already delivered)
 *     and feeds {@link CapabilityService}: 131026 strikes, 131049 backs off.
 *     131050 withdraws WhatsApp marketing; a 1320xx template error pauses the
 *     template.</li>
 * <li>Spend is booked here, once per message, in the category Meta billed
 *     ({@code pricing.category}); a different category than sent is logged
 *     and counted ({@code engage_wa_category_drift_total}).</li>
 * <li>{@code wa_read} and {@code wa_failed} events let the cascade runner
 *     (worker) treat a read as success and a 131026/131049 as "escalate now".</li>
 * </ul>
 *
 * <p><b>Inbound</b> ({@code messages.messages[]}), once per message id
 * ({@code wa_inbound}):
 * <ul>
 * <li>Opens the 24 h service window and makes the number CAPABLE.</li>
 * <li>STOP (English, Hinglish, Hindi): withdraws WhatsApp transactional and
 *     marketing and marketing on every other channel, cancels live cascades
 *     ({@code outcome = stopped}), and emits {@code wa_stop} so the worker
 *     sends one confirmation inside the window it just opened.</li>
 * <li>START: grants WhatsApp again. "Stop promotions" (Meta's quick reply):
 *     withdraws WhatsApp marketing only. The registered in-thread opt-in text
 *     ({@code wa_inthread_v1}): grants what that copy covers.</li>
 * <li>Quick-reply payloads become events; anything else is {@code wa_message_received}.</li>
 * </ul>
 * No message text is stored.
 */
@Singleton
public class MetaInboxHandler implements InboxHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MetaInboxHandler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Pattern STOP = Pattern.compile(
            "^\\s*(stop|unsubscribe|opt\\s?out|band karo|bandh karo|mat bhejo|nahi chahiye|रुको|बंद करो)\\s*[.!]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern START = Pattern.compile(
            "^\\s*(start|resume|subscribe|shuru karo|chalu karo)\\s*[.!]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Set<String> QUICK_REPLIES = Set.of("wa_optin_yes", "ndr_reschedule", "fit_ok");
    static final String INTHREAD_COPY = "wa_inthread_v1";

    private final IdentityResolver identities;
    private final ConsentWriter consents;
    private final CapabilityService capability;
    private final EventWriter events;
    private final IngestMetrics metrics;

    public MetaInboxHandler(IdentityResolver identities, ConsentWriter consents, CapabilityService capability,
                            EventWriter events, IngestMetrics metrics) {
        this.identities = identities;
        this.consents = consents;
        this.capability = capability;
        this.events = events;
        this.metrics = metrics;
    }

    @Override
    public String source() {
        return "meta";
    }

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        switch (item.topic()) {
            case "messages" -> {
                statuses(c, item.deliveryId());
                inbound(c, item.deliveryId());
            }
            // Template status/quality and number quality: P4-T03.
            default -> LOG.info("meta webhook topic {} acknowledged; not handled yet", item.topic());
        }
    }

    /* ------------------------------ statuses ------------------------------ */

    private record Send(long id, UUID identityId, String category, String templateKey, Long runId) {}

    private void statuses(Connection c, String deliveryId) throws SQLException {
        record Status(String wamid, String status, Instant at, String recipient, Integer code,
                      Boolean billable, String billed) {}
        var list = new ArrayList<Status>();
        try (var ps = Sql.prepare(c, """
                SELECT s.v->>'id' AS wamid, s.v->>'status' AS status, (s.v->>'timestamp')::bigint AS ts,
                       s.v->>'recipient_id' AS recipient, (s.v#>>'{errors,0,code}')::int AS code,
                       (s.v#>>'{pricing,billable}')::boolean AS billable, s.v#>>'{pricing,category}' AS billed
                  FROM webhook_inbox i
                 CROSS JOIN LATERAL jsonb_path_query(i.payload, '$.entry[*].changes[*].value.statuses[*]') AS s(v)
                 WHERE i.source = 'meta' AND i.delivery_id = ?""", deliveryId);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Status(rs.getString("wamid"), rs.getString("status"), Instant.ofEpochSecond(rs.getLong("ts")),
                        rs.getString("recipient"), (Integer) rs.getObject("code"), (Boolean) rs.getObject("billable"),
                        rs.getString("billed")));
            }
        }
        for (var s : list) {
            var send = send(c, s.wamid());
            if (send == null) {
                LOG.debug("status {} for an unknown message {}", s.status(), s.wamid());
                continue;
            }
            switch (s.status()) {
                case "sent" -> Sql.update(c, "UPDATE sends SET sent_at = COALESCE(sent_at, ?) WHERE id = ?", utc(s.at()), send.id());
                case "delivered" -> {
                    Sql.update(c, """
                            UPDATE sends SET delivered_at = COALESCE(delivered_at, ?),
                                   status = CASE WHEN status IN ('queued', 'sent') THEN 'delivered'::send_status ELSE status END
                             WHERE id = ?""", utc(s.at()), send.id());
                    capability.observe(c, send.identityId(), Channel.WHATSAPP, DeliveryOutcome.of(DeliveryOutcome.Type.DELIVERED, s.at()));
                }
                case "read" -> {
                    Sql.update(c, """
                            UPDATE sends SET delivered_at = COALESCE(delivered_at, ?), read_at = COALESCE(read_at, ?),
                                   status = CASE WHEN status IN ('queued', 'sent', 'delivered') THEN 'read'::send_status ELSE status END
                             WHERE id = ?""", utc(s.at()), utc(s.at()), send.id());
                    capability.observe(c, send.identityId(), Channel.WHATSAPP, DeliveryOutcome.of(DeliveryOutcome.Type.READ, s.at()));
                    events.write(c, send.identityId(), "wa_read", "meta", "wa_read:" + s.wamid(), signal(send));
                }
                case "failed" -> failed(c, send, s.wamid(), s.code(), s.recipient(), s.at());
                default -> LOG.debug("status {} ignored", s.status());
            }
            if (s.billable() != null) bill(c, send, s.wamid(), s.billable(), s.billed(), s.at());
        }
    }

    private void failed(Connection c, Send send, String wamid, Integer code, String recipient, Instant at) throws SQLException {
        var reason = code == null ? "wa_failed" : "wa_" + code;
        Sql.update(c, """
                UPDATE sends SET status = 'failed', failed_reason = ? WHERE id = ? AND status IN ('queued', 'sent')""",
                reason, send.id());
        if (code != null) {
            capability.observe(c, send.identityId(), Channel.WHATSAPP,
                    DeliveryOutcome.failed(code, category(send.category()), recipient, at));
            if (code == 131050) {
                consents.statement(c, send.identityId(), "withdrawn", "wa_131050", null, "wa_131050:" + wamid, at,
                        List.<String[]>of(new String[] {"whatsapp", "marketing"}));
            } else if (code >= 132000 && code <= 132015) {
                Sql.update(c, "UPDATE templates SET status = 'paused', updated_at = now() WHERE key = ?", send.templateKey());
                LOG.error("WhatsApp template {} failed with {}: paused it. Check it in WhatsApp Manager.", send.templateKey(), code);
            } else if (code == 131047) {
                LOG.error("send {} failed 131047 (outside the 24 h window): the service-window logic let it through", send.id());
            }
        }
        var props = signal(send);
        props.put("code", code == null ? 0 : code);
        events.write(c, send.identityId(), "wa_failed", "meta", "wa_failed:" + wamid, props);
    }

    /** Once per message: what Meta billed, in the category it billed, at the configured rate. */
    private void bill(Connection c, Send send, String wamid, boolean billable, String billed, Instant at) throws SQLException {
        var cat = billed == null ? null : billed.toLowerCase(Locale.ROOT);
        if (cat != null && !Set.of("marketing", "utility", "authentication", "service").contains(cat)) {
            LOG.warn("message {} billed in category '{}', which spend_ledger does not know; booked as marketing", wamid, billed);
            cat = "marketing";
        }
        long paise = billable && cat != null ? rate(c, cat) : 0;
        int marked = Sql.update(c, """
                UPDATE sends SET billed_at = ?, billed_category = CAST(? AS msg_category), cost_paise = ?
                 WHERE id = ? AND billed_at IS NULL""", utc(at), cat, (int) paise, send.id());
        if (marked == 0) return;                                             // booked already: a replay
        if (billable && cat != null) {
            Sql.update(c, """
                    INSERT INTO spend_ledger (day, channel, category, messages, paise)
                    VALUES (CAST(? AS date), 'whatsapp', CAST(? AS msg_category), 1, ?)
                    ON CONFLICT (day, channel, category) DO UPDATE
                       SET messages = spend_ledger.messages + 1, paise = spend_ledger.paise + EXCLUDED.paise""",
                    at.atZone(IST).toLocalDate().toString(), cat, paise);
        }
        if (cat != null && !cat.equals(send.category())) {
            LOG.warn("message {} sent as {} was billed as {}: template {} may have been re-classified by Meta",
                    wamid, send.category(), cat, send.templateKey());
            metrics.waCategoryDrift(send.category(), cat);
        }
    }

    private static long rate(Connection c, String category) throws SQLException {
        var key = "rate.whatsapp." + category + "_paise";
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE((SELECT (value #>> '{}')::bigint FROM config_current WHERE key = ? AND selector = '*'),
                                (SELECT (default_value #>> '{}')::bigint FROM config_keys WHERE key = ?), 0)""", key, key);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Send send(Connection c, String wamid) throws SQLException {
        if (wamid == null) return null;
        try (var ps = Sql.prepare(c, """
                SELECT id, identity_id, category::text AS category, template_key, cascade_run_id
                  FROM sends WHERE provider_id = ? AND channel = 'whatsapp'""", wamid);
             var rs = ps.executeQuery()) {
            return rs.next() ? new Send(rs.getLong("id"), rs.getObject("identity_id", UUID.class), rs.getString("category"),
                    rs.getString("template_key"), Sql.nullableLong(rs, "cascade_run_id")) : null;
        }
    }

    private static Map<String, Object> signal(Send send) {
        var props = new HashMap<String, Object>();
        props.put("send_id", send.id());
        if (send.runId() != null) props.put("run_id", send.runId());
        return props;
    }

    /* ------------------------------ inbound ------------------------------ */

    private void inbound(Connection c, String deliveryId) throws SQLException {
        record Msg(String id, String from, Instant at, String type, String text, String payload, String buttonText, String context) {}
        var list = new ArrayList<Msg>();
        try (var ps = Sql.prepare(c, """
                SELECT m.v->>'id' AS id, m.v->>'from' AS sender, (m.v->>'timestamp')::bigint AS ts, m.v->>'type' AS type,
                       m.v#>>'{text,body}' AS body,
                       COALESCE(m.v#>>'{button,payload}', m.v#>>'{interactive,button_reply,id}',
                                m.v#>>'{interactive,list_reply,id}') AS payload,
                       COALESCE(m.v#>>'{button,text}', m.v#>>'{interactive,button_reply,title}') AS button_text,
                       m.v#>>'{context,id}' AS context
                  FROM webhook_inbox i
                 CROSS JOIN LATERAL jsonb_path_query(i.payload, '$.entry[*].changes[*].value.messages[*]') AS m(v)
                 WHERE i.source = 'meta' AND i.delivery_id = ?""", deliveryId);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Msg(rs.getString("id"), rs.getString("sender"), Instant.ofEpochSecond(rs.getLong("ts")),
                        rs.getString("type"), rs.getString("body"), rs.getString("payload"), rs.getString("button_text"),
                        rs.getString("context")));
            }
        }
        for (var m : list) {
            var msisdn = Msisdn.normalise(m.from()).orElse(null);
            if (msisdn == null || m.id() == null) {
                LOG.warn("inbound WhatsApp message without a usable sender; ignored");
                continue;
            }
            var identity = identities.resolve(c, List.of(Key.verified("phone", msisdn), Key.verified("wa_id", m.from())));
            var action = action(c, m.text(), m.payload(), m.buttonText());
            var kind = Set.of("text", "button", "interactive").contains(m.type()) ? m.type() : "other";
            int fresh = Sql.update(c, """
                    INSERT INTO wa_inbound (message_id, identity_id, kind, action, sent_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (message_id) DO NOTHING""", m.id(), identity, kind, action, utc(m.at()));
            if (fresh == 0) continue;                                       // a replay: everything below happened

            Sql.update(c, """
                    INSERT INTO profiles (identity_id, wa_window_until) VALUES (?, ?)
                    ON CONFLICT (identity_id) DO UPDATE
                       SET wa_window_until = GREATEST(profiles.wa_window_until, EXCLUDED.wa_window_until), updated_at = now()""",
                    identity, utc(m.at().plusSeconds(24 * 3600)));
            capability.markCapable(c, identity, Channel.WHATSAPP, m.at());

            var ref = "wa:" + m.id();
            switch (action == null ? "" : action) {
                case "stop" -> {
                    consents.statement(c, identity, "withdrawn", "wa_stop_reply", null, ref, m.at(), List.of(
                            new String[] {"whatsapp", "transactional"}, new String[] {"whatsapp", "marketing"},
                            new String[] {"push", "marketing"}, new String[] {"email", "marketing"},
                            new String[] {"sms", "marketing"}));
                    int cancelled = Sql.update(c, """
                            UPDATE cascade_runs SET status = 'cancelled', outcome = 'stopped', updated_at = now()
                             WHERE identity_id = ? AND status IN ('active', 'waiting')""", identity);
                    events.write(c, identity, "wa_stop", "meta", "wa_stop:" + m.id(),
                            Map.<String, Object>of("message_id", m.id(), "cascades_cancelled", cancelled));
                }
                case "start" -> {
                    consents.statement(c, identity, "granted", "wa_start_reply", null, ref, m.at(), List.of(
                            new String[] {"whatsapp", "transactional"}, new String[] {"whatsapp", "marketing"}));
                    events.write(c, identity, "wa_start", "meta", "wa_start:" + m.id(), Map.<String, Object>of("message_id", m.id()));
                }
                case "stop_promotions" -> consents.statement(c, identity, "withdrawn", "wa_stop_promotions", null, ref, m.at(),
                        List.<String[]>of(new String[] {"whatsapp", "marketing"}));
                case "optin" -> optIn(c, identity, ref, m.at());
                default -> {
                    var name = m.payload() != null && QUICK_REPLIES.contains(m.payload()) ? m.payload() : "wa_message_received";
                    var props = new HashMap<String, Object>();
                    props.put("message_id", m.id());
                    props.put("type", kind);
                    if (m.context() != null) props.put("context_wamid", m.context());
                    events.write(c, identity, name, "meta", "wa_msg:" + m.id(), props);
                }
            }
        }
    }

    /** STOP, START, Meta's "Stop promotions" quick reply, or the registered in-thread opt-in text. */
    static String action(Connection c, String text, String payload, String buttonText) throws SQLException {
        if (text != null && STOP.matcher(text).matches()) return "stop";
        if (text != null && START.matcher(text).matches()) return "start";
        for (var s : new String[] {payload, buttonText}) {
            if (s != null && s.strip().equalsIgnoreCase("stop promotions")) return "stop_promotions";
        }
        if (text != null && c != null) {
            try (var ps = Sql.prepare(c, """
                    SELECT 1 FROM consent_copy_versions
                     WHERE version = ? AND lower(btrim(text)) = lower(btrim(?))""", INTHREAD_COPY, text);
                 var rs = ps.executeQuery()) {
                if (rs.next()) return "optin";
            }
        }
        return null;
    }

    private void optIn(Connection c, UUID identity, String ref, Instant at) throws SQLException {
        var targets = new ArrayList<String[]>();
        try (var ps = Sql.prepare(c, "SELECT unnest(purposes)::text FROM consent_copy_versions WHERE version = ?", INTHREAD_COPY);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) targets.add(new String[] {"whatsapp", rs.getString(1)});
        }
        if (!targets.isEmpty()) consents.statement(c, identity, "granted", "wa_thread", INTHREAD_COPY, ref, at, targets);
    }

    private static Category category(String dbName) {
        return dbName == null ? null : Category.valueOf(dbName.toUpperCase(Locale.ROOT));
    }

    private static java.time.OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
