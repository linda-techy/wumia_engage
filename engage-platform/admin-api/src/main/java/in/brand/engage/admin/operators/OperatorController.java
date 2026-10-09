package in.brand.engage.admin.operators;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.PasswordHasher;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.auth.SessionRepository;
import in.brand.engage.admin.auth.Tokens;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Operator management, OWNER only. Every change writes its audit row in the
 * same transaction. Invites and resets return a one-time set-password link
 * (72 h) for the owner to pass on; the console never emails it.
 *
 * <p>Guards: the last active OWNER cannot lose OWNER or be disabled, and an
 * owner cannot disable themselves.
 */
@Controller("/api/operators")
@ExecuteOn(TaskExecutors.BLOCKING)
public class OperatorController {

    static final Duration LINK_TTL = Duration.ofHours(72);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    @Serdeable
    public record OperatorRow(String id, String email, String fullName, String status, boolean mfaEnrolled,
                              List<String> roles, OffsetDateTime lastLoginAt, OffsetDateTime createdAt) {}

    @Serdeable
    public record Invite(String email, String fullName, List<String> roles) {}

    @Serdeable
    public record Roles(List<String> roles) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final Tokens tokens;
    private final PasswordHasher hasher;
    private final SessionRepository sessions;
    private final ObjectMapper json;

    public OperatorController(Db db, CurrentOperator current, AuditLog audit, Tokens tokens, PasswordHasher hasher,
                              SessionRepository sessions, ObjectMapper json) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.tokens = tokens;
        this.hasher = hasher;
        this.sessions = sessions;
        this.json = json;
    }

    @RequiresRole(Role.OWNER)
    @Get
    public List<OperatorRow> list() {
        return db.inTx(c -> {
            var out = new ArrayList<OperatorRow>();
            try (var ps = Sql.prepare(c, SqlFiles.get("operator_list.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new OperatorRow(rs.getString("id"), rs.getString("email"), rs.getString("full_name"),
                            rs.getString("status"), rs.getBoolean("mfa_enrolled"),
                            Arrays.asList((String[]) rs.getArray("roles").getArray()),
                            Sql.timestamp(rs, "last_login_at"), Sql.timestamp(rs, "created_at")));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.OWNER)
    @Post
    public HttpResponse<Map<String, Object>> invite(@Body Invite body) {
        if (body == null || body.email() == null || !EMAIL.matcher(body.email().strip()).matches()) {
            throw Problems.badRequest("a valid email is required");
        }
        if (body.fullName() == null || body.fullName().isBlank()) throw Problems.badRequest("fullName is required");
        var roles = roles(body.roles());
        var email = body.email().strip().toLowerCase(Locale.ROOT);
        var actor = current.id();
        var placeholder = hasher.hash(UUID.randomUUID().toString().toCharArray());   // unusable until the link is used
        var id = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM operators WHERE email = ?", email); var rs = ps.executeQuery()) {
                if (rs.next()) throw Problems.conflict("operator-exists", email + " already has an account");
            }
            var newId = UUID.randomUUID();
            Sql.update(c, """
                    INSERT INTO operators (id, email, full_name, password_hash, status) VALUES (?, ?, ?, ?, 'invited')""",
                    newId, email, body.fullName().strip(), placeholder);
            setRoles(c, newId, roles, actor);
            audit.record(c, actor, "operator.invite", "operator", newId.toString(), null,
                    toJson(Map.of("email", email, "roles", roles)));
            return newId;
        });
        return HttpResponse.created(Map.of("id", id.toString(), "setPasswordLink", link(id)));
    }

    @RequiresRole(Role.OWNER)
    @Put("/{id}/roles")
    public Map<String, Object> setRoles(@PathVariable String id, @Body Roles body) {
        var operatorId = parse(id);
        var roles = roles(body == null ? null : body.roles());
        var actor = current.id();
        db.inTx(c -> {
            var before = currentRoles(c, operatorId);
            if (before.contains("OWNER") && !roles.contains("OWNER")) requireAnotherOwner(c, operatorId);
            Sql.update(c, "DELETE FROM operator_roles WHERE operator_id = ?", operatorId);
            setRoles(c, operatorId, roles, actor);
            audit.record(c, actor, "operator.roles", "operator", operatorId.toString(),
                    toJson(Map.of("roles", before)), toJson(Map.of("roles", roles)));
            return null;
        });
        return Map.of("id", operatorId.toString(), "roles", roles);
    }

    @RequiresRole(Role.OWNER)
    @Post("/{id}/disable")
    public HttpResponse<?> disable(@PathVariable String id) {
        var operatorId = parse(id);
        var actor = current.id();
        if (operatorId.equals(actor)) throw Problems.conflict("cannot-disable-self", "you cannot disable your own account");
        db.inTx(c -> {
            if (currentRoles(c, operatorId).contains("OWNER")) requireAnotherOwner(c, operatorId);
            Sql.update(c, "UPDATE operators SET status = 'disabled', updated_at = now() WHERE id = ?", operatorId);
            sessions.revokeAllFor(c, operatorId, "operator_disabled");
            audit.record(c, actor, "operator.disable", "operator", operatorId.toString(), null, null);
            return null;
        });
        return HttpResponse.noContent();
    }

    /** Forgot password or lost phone: new link, MFA cleared, every session revoked. */
    @RequiresRole(Role.OWNER)
    @Post("/{id}/reset")
    public Map<String, Object> reset(@PathVariable String id) {
        var operatorId = parse(id);
        var actor = current.id();
        var placeholder = hasher.hash(UUID.randomUUID().toString().toCharArray());
        db.inTx(c -> {
            // The placeholder password moves ver (setPassword semantics), so old access tokens die too.
            int n = Sql.update(c, """
                    UPDATE operators
                       SET password_hash = ?, mfa_secret_enc = NULL, mfa_enrolled_at = NULL, status = 'invited',
                           password_changed_at = GREATEST(now(), password_changed_at + interval '1 second'),
                           failed_logins = 0, locked_until = NULL, updated_at = now()
                     WHERE id = ?""", placeholder, operatorId);
            if (n == 0) throw Problems.notFound("no such operator");
            Sql.update(c, "DELETE FROM operator_recovery_codes WHERE operator_id = ?", operatorId);
            sessions.revokeAllFor(c, operatorId, "operator_reset");
            audit.record(c, actor, "operator.reset", "operator", operatorId.toString(), null, null);
            return null;
        });
        return Map.of("id", operatorId.toString(), "setPasswordLink", link(operatorId));
    }

    private String link(UUID id) {
        var ver = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT password_changed_at FROM operators WHERE id = ?", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no such operator");
                return (int) (Sql.timestamp(rs, "password_changed_at").toEpochSecond() % Integer.MAX_VALUE);
            }
        });
        return "/set-password?token=" + tokens.issuePurpose(id, "set-password", Map.of("pwd", String.valueOf(ver)), LINK_TTL);
    }

    private static List<String> roles(List<String> requested) {
        if (requested == null || requested.isEmpty()) throw Problems.badRequest("at least one role is required");
        var out = new TreeSet<String>();
        for (var r : requested) {
            try {
                out.add(Role.valueOf(r).name());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw Problems.badRequest("unknown role " + r + "; roles are " + Arrays.toString(Role.values()));
            }
        }
        return List.copyOf(out);
    }

    private static void setRoles(Connection c, UUID operatorId, List<String> roles, UUID grantedBy) throws SQLException {
        for (var role : roles) {
            Sql.update(c, "INSERT INTO operator_roles (operator_id, role, granted_by) VALUES (?, ?, ?)",
                    operatorId, role, grantedBy);
        }
    }

    private static List<String> currentRoles(Connection c, UUID operatorId) throws SQLException {
        var out = new ArrayList<String>();
        try (var ps = Sql.prepare(c, "SELECT 1 FROM operators WHERE id = ? FOR UPDATE", operatorId); var rs = ps.executeQuery()) {
            if (!rs.next()) throw Problems.notFound("no such operator");
        }
        try (var ps = Sql.prepare(c, "SELECT role FROM operator_roles WHERE operator_id = ? ORDER BY role", operatorId);
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Another active OWNER must remain; serialised so two owners cannot demote each other at once. */
    private static void requireAnotherOwner(Connection c, UUID leaving) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended('operators:owners', 0))");
             var rs = ps.executeQuery()) {
            rs.next();
        }
        try (var ps = Sql.prepare(c, """
                SELECT count(*) FROM operators o JOIN operator_roles r ON r.operator_id = o.id
                 WHERE r.role = 'OWNER' AND o.status = 'active' AND o.id <> ?""", leaving);
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) == 0) throw Problems.conflict("last-owner", "there must always be at least one active OWNER");
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Problems.notFound("no such operator");
        }
    }
}
