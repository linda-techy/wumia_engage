package in.brand.engage.admin.dashboard;

import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The operations dashboard in one read, polled every 30 s by the console.
 * Reads the V16 {@code dash_*} views and the V4/V11 views only, never raw
 * tables: what a number means is defined once, in the migration.
 *
 * <p>Not here yet: WhatsApp number quality and tier (P4 builds the number
 * table), and the weekly trend of the UNKNOWN capability share (needs a
 * history the capability table does not keep).
 */
@Controller("/api/dashboard")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DashboardController {

    private final Db db;

    public DashboardController(Db db) {
        this.db = db;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public Map<String, Object> dashboard() {
        return db.inTx(c -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC));
            out.put("spendToday", Rows.list(c, "SELECT * FROM dash_spend_today ORDER BY channel, category"));
            out.put("sends24h", Rows.list(c, "SELECT * FROM dash_sends_24h ORDER BY sends DESC, channel, status"));
            out.put("journeys", Rows.list(c, "SELECT * FROM dash_journey_health ORDER BY intent_key"));
            out.put("consentDaily", Rows.list(c, "SELECT * FROM dash_consent_daily ORDER BY day_ist DESC, channel, state"));
            out.put("capability", Rows.list(c, "SELECT * FROM dash_capability ORDER BY channel, state"));
            out.put("pushDevices", Rows.list(c, "SELECT * FROM dash_push_devices ORDER BY browser, state"));
            out.put("pushFunnel7d", Rows.list(c, """
                    SELECT surface, sum(soft_shown) AS soft_shown, sum(soft_accepted) AS soft_accepted,
                           sum(native_granted) AS native_granted, sum(token_minted) AS token_minted,
                           sum(ios_redirected) AS ios_redirected
                      FROM push_prompt_funnel
                     WHERE day_ist > (now() AT TIME ZONE 'Asia/Kolkata')::date - 7
                     GROUP BY surface ORDER BY surface"""));
            out.put("waTemplates", Rows.list(c, "SELECT * FROM dash_wa_templates ORDER BY status, quality"));
            out.put("waTemplateMismatches", Rows.list(c,
                    "SELECT * FROM wa_template_category_mismatch ORDER BY key, language"));
            return out;
        });
    }
}
