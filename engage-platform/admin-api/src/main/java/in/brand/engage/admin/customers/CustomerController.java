package in.brand.engage.admin.customers;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.privacy.PiiAccess;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;
import java.util.UUID;

/**
 * Customer lookup and 360 view (Console v0). Exact match on a full email or a
 * phone only, so the customer base cannot be enumerated. Contact details are
 * masked; {@code reveal} unmasks one field (phone or email) for an ANALYST,
 * with a reason, writing {@code pii_unmask_log} and the audit row in the same
 * transaction as the read, within the shared 50-a-day limit ({@link PiiAccess}).
 */
@Controller("/api/customers")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CustomerController {

    private final Db db;
    private final CustomerQueries queries;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final PiiAccess pii;

    @Serdeable
    public record Reveal(@Nullable String field, @Nullable String reason) {}

    public CustomerController(Db db, CustomerQueries queries, CurrentOperator current, AuditLog audit, PiiAccess pii) {
        this.db = db;
        this.queries = queries;
        this.current = current;
        this.audit = audit;
        this.pii = pii;
    }

    @RequiresRole(Role.VIEWER)
    @Get
    public Map<String, Object> lookup(@Nullable @QueryValue String q) {
        var key = CustomerQueries.normalise(q).orElseThrow(() -> Problems.notFound("no customer matches exactly"));
        var id = db.inTx(c -> queries.lookup(c, key)).orElseThrow(() -> Problems.notFound("no customer matches exactly"));
        return Map.of("identityId", id.toString());
    }

    @RequiresRole(Role.VIEWER)
    @Get("/{id}")
    public Map<String, Object> view(@PathVariable String id) {
        var identity = parse(id);
        return db.inTx(c -> {
            if (!queries.exists(c, identity)) throw Problems.notFound("no such customer");
            return queries.view(c, identity);
        });
    }

    /** One field at a time, with a reason: {@code {"field": "phone" | "email", "reason": "..."}}. */
    @RequiresRole(Role.ANALYST)
    @Post("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable String id, @Nullable @Body Reveal body) {
        var identity = parse(id);
        var field = body == null ? null : body.field();
        if (!"phone".equals(field) && !"email".equals(field)) throw Problems.badRequest("field is phone or email");
        var reason = PiiAccess.reason(body.reason());
        var operator = current.id();
        return db.inTx(c -> {
            if (!queries.exists(c, identity)) throw Problems.notFound("no such customer");
            pii.record(c, operator, identity, field, reason);
            var keys = queries.keys(c, identity, true).stream().filter(k -> field.equals(k.get("kind"))).toList();
            audit.record(c, operator, "customer.reveal", "identity", identity.toString(), null,
                    "{\"field\":\"" + field + "\"}");
            return Map.<String, Object>of("field", field, "keys", keys);
        });
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such customer");
        }
    }
}
