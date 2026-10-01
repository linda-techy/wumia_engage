package in.brand.engage.admin.customers;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;
import java.util.UUID;

/**
 * Customer lookup and 360 view (Console v0). Exact match on a full email or a
 * phone only, so the customer base cannot be enumerated. Contact details are
 * masked; {@code reveal} unmasks them for an ANALYST and writes the audit row
 * in the same transaction as the read.
 */
@Controller("/api/customers")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CustomerController {

    private final Db db;
    private final CustomerQueries queries;
    private final CurrentOperator current;
    private final AuditLog audit;

    public CustomerController(Db db, CustomerQueries queries, CurrentOperator current, AuditLog audit) {
        this.db = db;
        this.queries = queries;
        this.current = current;
        this.audit = audit;
    }

    @Get
    public Map<String, Object> lookup(@Nullable @QueryValue String q) {
        current.require("VIEWER");
        var key = CustomerQueries.normalise(q).orElseThrow(() -> Problems.notFound("no customer matches exactly"));
        var id = db.inTx(c -> queries.lookup(c, key)).orElseThrow(() -> Problems.notFound("no customer matches exactly"));
        return Map.of("identityId", id.toString());
    }

    @Get("/{id}")
    public Map<String, Object> view(@PathVariable String id) {
        current.require("VIEWER");
        var identity = parse(id);
        return db.inTx(c -> {
            if (!queries.exists(c, identity)) throw Problems.notFound("no such customer");
            return queries.view(c, identity);
        });
    }

    @Post("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable String id) {
        current.require("ANALYST");
        var identity = parse(id);
        var operator = current.id();
        return db.inTx(c -> {
            if (!queries.exists(c, identity)) throw Problems.notFound("no such customer");
            var keys = queries.keys(c, identity, true).stream()
                    .filter(k -> "email".equals(k.get("kind")) || "phone".equals(k.get("kind")))
                    .toList();
            audit.record(c, operator, "customer.reveal", "identity", identity.toString(), null, null);
            return Map.<String, Object>of("keys", keys);
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
