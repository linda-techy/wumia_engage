package in.brand.engage.admin.inbox;

import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.StaffPushKeys;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * New-message alerts on staff devices (V50): the console subscribes the
 * browser with Engage's public VAPID key and registers the endpoint here; the
 * worker pings it. Only the browsers' own push services are accepted as
 * endpoints, since the worker makes requests to them.
 */
@Controller("/api/staff-push")
@ExecuteOn(TaskExecutors.BLOCKING)
public class StaffPushController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StaffPushController.class);

    /** Chrome/Edge/Android (FCM), Firefox, Safari/iOS, Windows. */
    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            ".push.services.mozilla.com", "web.push.apple.com", ".push.apple.com", ".notify.windows.com");

    @Serdeable
    public record Subscription(String endpoint) {}

    private final Db db;
    private final CurrentOperator current;

    public StaffPushController(Db db, CurrentOperator current) {
        this.db = db;
        this.current = current;
    }

    @RequiresRole(Role.ANALYST)
    @Get("/key")
    public Map<String, Object> key() {
        return Map.of("publicKey", db.inTx(c -> StaffPushKeys.load(c).publicKey()));
    }

    /** Is this browser's endpoint registered (to show "Alerts on")? */
    @RequiresRole(Role.ANALYST)
    @Post("/status")
    public Map<String, Object> status(@Body Subscription body) {
        var endpoint = body == null ? null : body.endpoint();
        var actor = current.id();
        boolean on = endpoint != null && db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM staff_push_subscriptions WHERE endpoint = ? AND operator_id = ?", endpoint, actor);
                 var rs = ps.executeQuery()) {
                return rs.next();
            }
        });
        return Map.of("on", on);
    }

    @RequiresRole(Role.ANALYST)
    @Post("/subscribe")
    public HttpResponse<Void> subscribe(@Body Subscription body, @Nullable @Header("User-Agent") String userAgent) {
        var endpoint = valid(body);
        var actor = current.id();
        var ua = userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length()));
        db.inTx(c -> Sql.update(c, """
                INSERT INTO staff_push_subscriptions (operator_id, endpoint, user_agent) VALUES (?, ?, ?)
                ON CONFLICT (endpoint) DO UPDATE SET operator_id = EXCLUDED.operator_id, user_agent = EXCLUDED.user_agent, failures = 0""",
                actor, endpoint, ua));
        LOG.info("staff alerts on for operator {} via {}", actor, URI.create(endpoint).getHost());
        return HttpResponse.noContent();
    }

    @RequiresRole(Role.ANALYST)
    @Post("/unsubscribe")
    public HttpResponse<Void> unsubscribe(@Body Subscription body) {
        var endpoint = body == null ? null : body.endpoint();
        var actor = current.id();
        if (endpoint != null) {
            int n = db.inTx(c -> Sql.update(c, "DELETE FROM staff_push_subscriptions WHERE endpoint = ? AND operator_id = ?", endpoint, actor));
            if (n > 0) LOG.info("staff alerts off for operator {}", actor);
        }
        return HttpResponse.noContent();
    }

    static String valid(Subscription body) {
        var endpoint = body == null ? null : body.endpoint();
        if (endpoint == null || endpoint.length() > 1000) throw Problems.badRequest("endpoint is required");
        URI u;
        try {
            u = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw Problems.badRequest("endpoint is not a URL");
        }
        var host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean known = PUSH_HOSTS.stream().anyMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h));
        if (!"https".equals(u.getScheme()) || u.getPort() != -1 || !known) {
            throw Problems.badRequest("endpoint is not a browser push service");
        }
        return endpoint;
    }
}
