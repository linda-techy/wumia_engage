package in.brand.engage.admin.auth;

import in.brand.engage.admin.web.Problems;
import io.micronaut.runtime.http.scope.RequestScope;
import java.util.List;
import java.util.UUID;

/**
 * The operator behind this request. Populated by {@link AuthFilter}; controllers
 * call {@link #require} explicitly rather than relying on annotations, so the
 * permission a handler needs is visible in the handler.
 */
@RequestScope
public class CurrentOperator {

    private UUID id;
    private UUID sessionId;
    private List<String> roles = List.of();

    void set(UUID id, UUID sessionId, List<String> roles) {
        this.id = id;
        this.sessionId = sessionId;
        this.roles = roles;
    }

    public UUID id() {
        if (id == null) throw Problems.unauthorized("not authenticated");
        return id;
    }

    public UUID sessionId() {
        id();
        return sessionId;
    }

    public List<String> roles() {
        return roles;
    }

    /**
     * OWNER holds everything. Every role includes reading (VIEWER): in
     * docs/03-auth-and-rbac.md each role is "+" on top of the read screens.
     * Nothing else is implied: a campaign role is not CONFIG_ADMIN or ANALYST.
     */
    public boolean has(String role) {
        if (roles.contains(role) || roles.contains("OWNER")) return true;
        return "VIEWER".equals(role) && !roles.isEmpty();
    }

    /** 401 when not signed in, 403 when signed in without the role. */
    public void require(String role) {
        id();
        if (!has(role)) throw Problems.forbidden("requires " + role);
    }
}
