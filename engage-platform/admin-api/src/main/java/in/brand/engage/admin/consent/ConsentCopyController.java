package in.brand.engage.admin.consent;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The consent copy registry: every wording a shopper can agree to, with how
 * many grants rest on it. Rows are immutable (a DB trigger, V4): new words
 * are a new version. Listing requires VIEWER; registering requires
 * CONFIG_ADMIN and is audited in the same transaction.
 */
@Controller("/api/consent-copy")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ConsentCopyController {

    static final Set<String> CHANNELS = Set.of("whatsapp", "push", "email", "sms", "rcs");
    static final Set<String> PURPOSES = Set.of("transactional", "marketing");
    static final Set<String> SURFACES = Set.of("cart", "thank_you", "soft_ask", "wa_thread", "checkout_notice");
    static final Pattern VERSION = Pattern.compile("[a-z][a-z0-9_]{1,40}_v[0-9]{1,3}");

    @Serdeable
    public record CopyVersion(String version, String channel, String surface, String text, List<String> purposes,
                              long grants, OffsetDateTime createdAt) {}

    @Serdeable
    public record Register(String version, String channel, String surface, String text, List<String> purposes) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;

    public ConsentCopyController(Db db, CurrentOperator current, AuditLog audit) {
        this.db = db;
        this.current = current;
        this.audit = audit;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public List<CopyVersion> list() {
        return db.inTx(c -> {
            var out = new ArrayList<CopyVersion>();
            try (var ps = Sql.prepare(c, SqlFiles.get("consent_copy_list.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CopyVersion(rs.getString("version"), rs.getString("channel"), rs.getString("surface"),
                            rs.getString("text"), Arrays.asList(rs.getString("purposes").split(",")),
                            rs.getLong("grants"), Sql.timestamp(rs, "created_at")));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.CONFIG_ADMIN)
    @Post
    public HttpResponse<?> register(@Body Register body) {
        var v = validate(body);
        var operator = current.id();
        db.inTx(c -> {
            int inserted = Sql.update(c, """
                    INSERT INTO consent_copy_versions (version, channel, text, purposes, surface, created_by)
                    VALUES (?, CAST(? AS channel), ?, CAST(? AS purpose[]), ?, ?)
                    ON CONFLICT (version) DO NOTHING""",
                    v.version(), v.channel(), v.text(), "{" + String.join(",", v.purposes()) + "}", v.surface(), operator);
            if (inserted == 0) {
                throw Problems.conflict("consent-copy-exists",
                        v.version() + " is already registered; wording is immutable, register a new version");
            }
            audit.record(c, operator, "consent_copy.register", "consent_copy_version", v.version(), null,
                    "{\"channel\":\"" + v.channel() + "\",\"surface\":\"" + v.surface() + "\"}");
            return null;
        });
        return HttpResponse.status(HttpStatus.CREATED);
    }

    static Register validate(Register b) {
        if (b == null) throw Problems.badRequest("body required");
        if (b.version() == null || !VERSION.matcher(b.version()).matches()) {
            throw Problems.badRequest("version must look like wa_cart_v2 (lower case, ending _v<number>)");
        }
        if (!CHANNELS.contains(b.channel())) throw Problems.badRequest("channel must be one of " + CHANNELS);
        if (!SURFACES.contains(b.surface())) throw Problems.badRequest("surface must be one of " + SURFACES);
        if (b.text() == null || b.text().isBlank() || b.text().length() > 1000) {
            throw Problems.badRequest("text is required (the exact words shown), at most 1000 characters");
        }
        if (b.purposes() == null || b.purposes().isEmpty() || !PURPOSES.containsAll(b.purposes())) {
            throw Problems.badRequest("purposes must be a non-empty subset of " + PURPOSES);
        }
        return new Register(b.version(), b.channel(), b.surface(), b.text(), List.copyOf(Set.copyOf(b.purposes())));
    }
}
