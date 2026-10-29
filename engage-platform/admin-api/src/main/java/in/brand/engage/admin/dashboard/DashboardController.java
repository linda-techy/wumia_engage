package in.brand.engage.admin.dashboard;

import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The dashboard in one read, polled every 30 s by the console.
 *
 * <p>Business results first (owner, 2026-10-30): revenue credited to messages
 * and its return on spend, carts and checkouts recovered, how many people can
 * be reached, chats waiting, and the WhatsApp number's health. Over a period of
 * {@code days} IST days ending today (1, 7 or 30; 7 by default), with the period
 * before it for comparison. Counts of people, never their identifiers.
 *
 * <p>Below that, operations health from the V16 {@code dash_*} views (spend
 * today, block reasons, journey health), which stay 24-hour or today windows.
 */
@Controller("/api/dashboard")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DashboardController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<Integer> PERIODS = Set.of(1, 7, 30);
    /** The journeys that win back a sale, and what the recovery card calls them. */
    static final String RECOVERY = "('cart_recovery', 'checkout_abandon')";
    /** A send's journey, as Analytics groups it. */
    static final String JOURNEY = "split_part(s.intent_key, ':', 1)";
    /** Revenue of a credited order: its total less refunds, else the conversion's value (Analytics, V41). */
    static final String REVENUE = "COALESCE(od.total_paise - od.refunded_paise, cv.value_paise)";

    private final Db db;

    public DashboardController(Db db) {
        this.db = db;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public Map<String, Object> dashboard(@Nullable @QueryValue Integer days) {
        int period = days == null ? 7 : days;
        if (!PERIODS.contains(period)) throw Problems.badRequest("days is 1, 7 or 30");
        var today = LocalDate.now(IST);
        var start = today.minusDays(period - 1L).atStartOfDay(IST).toOffsetDateTime();
        var prevStart = start.minusDays(period);
        var fromDay = today.minusDays(period - 1L);
        var prevFromDay = fromDay.minusDays(period);
        return db.inTx(c -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC));
            out.put("days", period);

            // Revenue from messages and the money spent on them, this period and the one before.
            out.put("headline", Rows.list(c, """
                    WITH credited AS (
                      SELECT cv.occurred_at, %s AS revenue
                        FROM conversions cv LEFT JOIN orders od ON od.id = cv.order_id
                       WHERE cv.last_send_id IS NOT NULL AND od.cancelled_at IS NULL AND cv.occurred_at >= ?
                    )
                    SELECT count(*) FILTER (WHERE occurred_at >= ?)                           AS orders,
                           COALESCE(sum(revenue) FILTER (WHERE occurred_at >= ?), 0)         AS revenue_paise,
                           count(*) FILTER (WHERE occurred_at < ?)                            AS prev_orders,
                           COALESCE(sum(revenue) FILTER (WHERE occurred_at < ?), 0)          AS prev_revenue_paise,
                           (SELECT COALESCE(sum(paise), 0) FROM spend_ledger WHERE day >= ?)  AS spend_paise,
                           (SELECT COALESCE(sum(paise), 0) FROM spend_ledger WHERE day >= ? AND day < ?) AS prev_spend_paise
                      FROM credited""".formatted(REVENUE),
                    prevStart, start, start, start, start, fromDay, prevFromDay, fromDay).getFirst());

            // Abandoned carts and checkouts: how many journeys started, and the orders credited to them.
            out.put("recovery", Rows.list(c, """
                    WITH j(journey) AS (VALUES ('cart_recovery'), ('checkout_abandon')),
                    started AS (
                      SELECT intent_key AS journey, count(*) AS started FROM cascade_runs
                       WHERE intent_key IN %1$s AND created_at >= ? GROUP BY 1
                    ), won AS (
                      SELECT %2$s AS journey, count(*) AS recovered, COALESCE(sum(%3$s), 0) AS revenue_paise
                        FROM conversions cv JOIN sends s ON s.id = cv.last_send_id
                        LEFT JOIN orders od ON od.id = cv.order_id
                       WHERE od.cancelled_at IS NULL AND cv.occurred_at >= ? AND %2$s IN %1$s
                       GROUP BY 1
                    )
                    SELECT j.journey, COALESCE(st.started, 0) AS started, COALESCE(w.recovered, 0) AS recovered,
                           COALESCE(w.revenue_paise, 0) AS revenue_paise
                      FROM j LEFT JOIN started st USING (journey) LEFT JOIN won w USING (journey)
                     ORDER BY j.journey""".formatted(RECOVERY, JOURNEY, REVENUE), start, start));

            // Who can be reached: offers on WhatsApp (marketing opt-in, not suppressed) and push (a live device).
            out.put("audience", Rows.list(c, """
                    WITH wa AS (
                      SELECT k.identity_id, k.occurred_at FROM consent_current k
                       WHERE k.channel = 'whatsapp' AND k.purpose = 'marketing' AND k.state = 'granted'
                         AND NOT EXISTS (SELECT 1 FROM suppressions x WHERE x.identity_id = k.identity_id
                                            AND x.channel = 'whatsapp' AND (x.until IS NULL OR x.until > now()))
                    ), push AS (
                      SELECT identity_id, min(created_at) AS first_at FROM devices
                       WHERE identity_id IN (SELECT identity_id FROM devices WHERE active)
                       GROUP BY identity_id
                    )
                    SELECT (SELECT count(*) FROM wa)                                  AS whatsapp,
                           (SELECT count(*) FROM wa WHERE occurred_at >= ?)           AS whatsapp_new,
                           (SELECT count(*) FROM push)                                AS push,
                           (SELECT count(*) FROM push WHERE first_at >= ?)            AS push_new""",
                    start, start).getFirst());

            // Chats whose last message is the customer's: someone should answer.
            out.put("inbox", Rows.list(c, """
                    SELECT count(*) FILTER (WHERE status = 'open' AND last_inbound_at >= last_message_at) AS waiting,
                           min(last_inbound_at) FILTER (WHERE status = 'open' AND last_inbound_at >= last_message_at) AS oldest_waiting_at,
                           count(*) FILTER (WHERE status = 'open' AND unread > 0) AS unread
                      FROM wa_threads""").getFirst());

            // The number Engage sends from (whatsapp.display_number), its quality and tier, and today's use of the tier:
            // Meta counts the people a business messaged first (templates) in a rolling 24 h.
            out.put("waNumber", Rows.list(c, """
                    SELECT n.display_number, n.verified_name, n.quality, n.messaging_tier,
                           (SELECT count(DISTINCT identity_id) FROM sends
                             WHERE channel = 'whatsapp' AND category IN ('marketing', 'utility')
                               AND sent_at > now() - interval '24 hours') AS used_24h
                      FROM wa_phone_numbers n
                     WHERE regexp_replace(n.display_number, '\\D', '', 'g') = (
                           SELECT COALESCE(cc.value, k.default_value) #>> '{}' FROM config_keys k
                             LEFT JOIN config_current cc ON cc.key = k.key AND cc.selector = '*'
                            WHERE k.key = 'whatsapp.display_number')"""));

            // Each channel's funnel over the period; WhatsApp chat replies (service) apart from templates.
            out.put("channels", Rows.list(c, """
                    SELECT CASE WHEN channel = 'whatsapp' AND category = 'service' THEN 'whatsapp_chat' ELSE channel::text END AS channel,
                           count(*) FILTER (WHERE sent_at IS NOT NULL)      AS sent,
                           count(*) FILTER (WHERE delivered_at IS NOT NULL) AS delivered,
                           count(*) FILTER (WHERE read_at IS NOT NULL)      AS read,
                           count(*) FILTER (WHERE clicked_at IS NOT NULL)   AS clicked,
                           count(*) FILTER (WHERE status = 'failed')        AS failed,
                           count(*) FILTER (WHERE status = 'blocked')       AS blocked
                      FROM sends WHERE created_at >= ?
                     GROUP BY 1 ORDER BY sent DESC, 1""", start));

            // The push prompt in people (one browser = one), the WhatsApp offer card apart, over the period.
            out.put("pushFunnel", Rows.list(c, """
                    SELECT count(DISTINCT anon_id) FILTER (WHERE step = 'soft_shown')    AS shown,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'soft_accepted') AS accepted,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'native_granted') AS allowed,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'native_denied')  AS blocked,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'native_denied' AND reason = 'auto') AS blocked_by_setting,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'native_dismissed') AS closed,
                           count(DISTINCT anon_id) FILTER (WHERE step = 'ios_redirected_to_whatsapp') AS cannot_push
                      FROM push_prompt_events WHERE surface <> 'wa_offer' AND created_at >= ?""", start).getFirst());
            out.put("waOfferFunnel", Rows.list(c, """
                    SELECT (SELECT count(DISTINCT anon_id) FROM push_prompt_events
                             WHERE surface = 'wa_offer' AND step = 'soft_shown' AND created_at >= ?)    AS shown,
                           (SELECT count(DISTINCT anon_id) FROM push_prompt_events
                             WHERE surface = 'wa_offer' AND step = 'soft_accepted' AND created_at >= ?) AS tapped,
                           (SELECT count(DISTINCT identity_id) FROM consents
                             WHERE channel = 'whatsapp' AND purpose = 'marketing' AND state = 'granted'
                               AND source = 'wa_offer_link' AND occurred_at >= ?)                          AS joined""",
                    start, start, start).getFirst());

            // Offers opt-ins and opt-outs in people per IST day and channel, 14 days.
            out.put("optIns", Rows.list(c, """
                    SELECT (occurred_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist, channel::text AS channel,
                           count(DISTINCT identity_id) FILTER (WHERE state = 'granted')   AS opted_in,
                           count(DISTINCT identity_id) FILTER (WHERE state = 'withdrawn') AS opted_out
                      FROM consents
                     WHERE purpose = 'marketing' AND occurred_at >= ?
                     GROUP BY 1, 2 ORDER BY 1, 2""", today.minusDays(13).atStartOfDay(IST).toOffsetDateTime()));

            // Operations health (V16 views).
            out.put("spendToday", Rows.list(c, "SELECT * FROM dash_spend_today ORDER BY channel, category"));
            out.put("sends24h", Rows.list(c, "SELECT * FROM dash_sends_24h ORDER BY sends DESC, channel, status"));
            out.put("journeys", Rows.list(c, "SELECT * FROM dash_journey_health ORDER BY intent_key"));
            out.put("capability", Rows.list(c, "SELECT * FROM dash_capability ORDER BY channel, state"));
            out.put("pushDevices", Rows.list(c, "SELECT * FROM dash_push_devices ORDER BY browser, state"));
            out.put("waTemplates", Rows.list(c, "SELECT * FROM dash_wa_templates ORDER BY status, quality"));
            out.put("waTemplateMismatches", Rows.list(c,
                    "SELECT * FROM wa_template_category_mismatch ORDER BY key, language"));
            return out;
        });
    }
}
