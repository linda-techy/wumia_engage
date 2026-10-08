package in.brand.engage.admin.templates;

import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.persistence.Db;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Template status (P6-T04): the registry mirror ({@code templates}, authored
 * in code) with Meta's state per language ({@code wa_templates}). A WhatsApp
 * template Meta approved in a different category than requested is flagged:
 * a utility template approved as marketing costs about 7x per send and loses
 * the quiet-hours exemption.
 *
 * <p>Read-only. Templates change in code and are synced from Meta (P4);
 * pausing one is a template-status change, not a console edit.
 */
@Controller("/api/templates")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TemplatesController {

    private final Db db;

    public TemplatesController(Db db) {
        this.db = db;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public List<Map<String, Object>> list() {
        return db.inTx(c -> {
            var meta = new HashMap<Object, List<Map<String, Object>>>();
            for (var row : Rows.list(c, """
                    SELECT key, language, provider_name, requested_category::text AS requested_category,
                           approved_category::text AS approved_category, status, quality, rejected_reason, synced_at,
                           approved_category IS NOT NULL AND approved_category <> requested_category AS category_mismatch
                      FROM wa_templates ORDER BY key, language""")) {
                meta.computeIfAbsent(row.remove("key"), k -> new ArrayList<>()).add(row);
            }
            var out = Rows.list(c, """
                    SELECT key, channel::text AS channel, category::text AS category, status, updated_at
                      FROM templates ORDER BY channel, key""");
            for (var t : out) {
                var whatsapp = meta.getOrDefault(t.get("key"), List.of());
                t.put("whatsapp", whatsapp);
                t.put("categoryMismatch", whatsapp.stream().anyMatch(w -> Boolean.TRUE.equals(w.get("categoryMismatch"))));
            }
            return out;
        });
    }
}
