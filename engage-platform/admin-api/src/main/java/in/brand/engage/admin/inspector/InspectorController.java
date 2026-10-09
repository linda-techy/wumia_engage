package in.brand.engage.admin.inspector;

import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.admin.web.Rows;
import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.privacy.Masks;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The journey inspector (P6-T04): "why did / didn't this customer get a
 * message". Exact phone match, then every cascade run with its attempts and
 * every send with its policy decision. Identifiers come back masked.
 *
 * <ul>
 * <li>POST, not {@code GET ?phone=}: a number in a URL lands in proxy logs
 *     and browser history.</li>
 * <li>ANALYST: a phone number in, a person's whole message history out.</li>
 * <li>Every lookup writes {@code pii_unmask_log} with the reason, a miss
 *     included (probing numbers is the pattern to catch), and counts toward
 *     the 50-per-operator-per-day limit.</li>
 * </ul>
 */
@Controller("/api/inspector")
@ExecuteOn(TaskExecutors.BLOCKING)
public class InspectorController {

    static final int MAX_ROWS = 200;

    @Serdeable
    public record Lookup(@Nullable String phone, @Nullable String reason) {}

    private final Db db;
    private final CurrentOperator current;
    private final ObjectMapper json;
    private final PiiAccess pii;

    public InspectorController(Db db, CurrentOperator current, ObjectMapper json, PiiAccess pii) {
        this.db = db;
        this.current = current;
        this.json = json;
        this.pii = pii;
    }

    @RequiresRole(Role.ANALYST)
    @Post
    public Map<String, Object> inspect(@Body Lookup body) {
        var phone = Msisdn.normalise(body == null ? null : body.phone())
                .orElseThrow(() -> Problems.badRequest("an Indian mobile number is required"));
        var reason = PiiAccess.reason(body.reason());
        var actor = current.id();

        var found = db.inTx(c -> {
            var identity = identityFor(c, phone);
            pii.record(c, actor, identity, "phone", reason);
            return identity;
        });
        if (found == null) throw Problems.notFound("no customer has this number");

        return db.inTx(c -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("identityId", found.toString());
            out.put("phone", Msisdn.mask(phone));
            out.put("emails", emails(c, found));
            out.put("runs", runs(c, found));
            out.put("sends", Rows.list(c, this::parse, """
                    SELECT id, channel::text AS channel, category::text AS category, template_key, intent_key, step_index,
                           status::text AS status, decision, failed_reason, cost_paise, config_snapshot_id, cascade_run_id,
                           created_at, sent_at, delivered_at, read_at, clicked_at
                      FROM sends WHERE identity_id = ? ORDER BY created_at DESC, id DESC LIMIT ?""", found, MAX_ROWS));
            return out;
        });
    }

    /** The identity holding this phone key, following a merge to the survivor. */
    private static UUID identityFor(Connection c, String phone) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE(i.merged_into, i.id)
                  FROM identity_keys k JOIN identities i ON i.id = k.identity_id
                 WHERE k.kind = 'phone' AND k.value = ?""", phone);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private static List<String> emails(Connection c, UUID identity) throws SQLException {
        var out = new ArrayList<String>();
        try (var ps = Sql.prepare(c, """
                SELECT value FROM identity_keys WHERE identity_id = ? AND kind = 'email' ORDER BY value""", identity);
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(Masks.email(rs.getString(1)));
        }
        return out;
    }

    private static List<Map<String, Object>> runs(Connection c, UUID identity) throws SQLException {
        var runs = Rows.list(c, """
                SELECT id, intent_key, subject_key, priority, step_index, status, outcome, next_step_at, deferrals,
                       created_at, updated_at
                  FROM cascade_runs WHERE identity_id = ? ORDER BY created_at DESC LIMIT ?""", identity, MAX_ROWS);
        var byRun = new HashMap<Object, List<Map<String, Object>>>();
        for (var a : Rows.list(c, """
                SELECT a.run_id, a.step_index, a.channel::text AS channel, a.result, a.reason, a.send_id, a.created_at
                  FROM cascade_attempts a JOIN cascade_runs r ON r.id = a.run_id
                 WHERE r.identity_id = ? ORDER BY a.run_id, a.step_index, a.id""", identity)) {
            byRun.computeIfAbsent(a.remove("runId"), k -> new ArrayList<>()).add(a);
        }
        for (var run : runs) run.put("attempts", byRun.getOrDefault(run.get("id"), List.of()));
        return runs;
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Argument.OBJECT_ARGUMENT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
