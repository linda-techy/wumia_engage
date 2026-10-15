package in.brand.engage.admin.privacy;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Erasure;
import in.brand.engage.persistence.Sql;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Privacy (P7-T05), all OWNER.
 *
 * <ul>
 * <li>{@code GET /api/privacy-requests?status=open|done}: Shopify's data
 *     requests, redactions and shop redactions, and erasures from the console.
 *     No identifiers in the list.</li>
 * <li>{@code POST /api/privacy-requests/{id}/report}: for an open data request,
 *     everything held about that customer, built on the spot (nothing is
 *     stored), and logged as a look at their details.</li>
 * <li>{@code POST /api/privacy-requests/{id}/resolve}: answered or handled,
 *     with a note; the identifiers kept for the request are emptied. Audited.</li>
 * <li>{@code POST /api/customers/{id}/erase}: DPDP erasure, reason required.
 *     Irreversible; recorded as a done request, audited by the database.</li>
 * </ul>
 */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class PrivacyController {

    @Serdeable
    public record PrivacyRequest(long id, String kind, String source, String externalRef, String status,
                                 boolean hasSubject, String result, String reason, String createdBy,
                                 OffsetDateTime createdAt, String resolvedBy, OffsetDateTime resolvedAt, String note) {}

    @Serdeable
    public record Resolve(@Nullable String note) {}

    @Serdeable
    public record Erase(@Nullable String reason) {}

    @Serdeable
    public record Erased(long requestId, String result) {}

    @Serdeable
    public record Key(String kind, String value, boolean verified, OffsetDateTime firstSeen) {}

    @Serdeable
    public record Consent(String channel, String purpose, String state, String source, OffsetDateTime occurredAt) {}

    @Serdeable
    public record Device(String platform, String browser, boolean active, OffsetDateTime createdAt) {}

    @Serdeable
    public record Message(String channel, String template, String status, OffsetDateTime createdAt) {}

    @Serdeable
    public record Order(String orderNumber, long totalPaise, String phone, String email, OffsetDateTime createdAt) {}

    @Serdeable
    public record Person(String profile, List<Key> keys, List<Consent> consents, List<Device> devices,
                         List<Message> messages, List<Order> orders, List<String> waitlist) {}

    @Serdeable
    public record Report(long requestId, String externalRef, OffsetDateTime generatedAt, String held,
                         List<Person> people, List<Order> requestedOrders) {}

    private final Db db;
    private final CurrentOperator current;
    private final AuditLog audit;
    private final PiiAccess pii;
    private final Erasure erasure;

    public PrivacyController(Db db, CurrentOperator current, AuditLog audit, PiiAccess pii, Erasure erasure) {
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.pii = pii;
        this.erasure = erasure;
    }

    @RequiresRole(Role.OWNER)
    @Get("/api/privacy-requests")
    public List<PrivacyRequest> list(@QueryValue(defaultValue = "open") String status) {
        if (!status.equals("open") && !status.equals("done")) throw Problems.badRequest("status is open or done");
        return db.inTx(c -> {
            var out = new ArrayList<PrivacyRequest>();
            try (var ps = Sql.prepare(c, """
                    SELECT r.id, r.kind, r.source, r.external_ref, r.status, r.subject <> '{}' AS has_subject,
                           r.result::text AS result, r.reason, cb.email AS created_by, r.created_at,
                           rb.email AS resolved_by, r.resolved_at, r.note
                      FROM privacy_requests r
                      LEFT JOIN operators cb ON cb.id = r.created_by
                      LEFT JOIN operators rb ON rb.id = r.resolved_by
                     WHERE r.status = ?
                     ORDER BY CASE WHEN r.status = 'open' THEN r.created_at END ASC, r.resolved_at DESC NULLS LAST
                     LIMIT 200""", status);
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PrivacyRequest(rs.getLong("id"), rs.getString("kind"), rs.getString("source"),
                            rs.getString("external_ref"), rs.getString("status"), rs.getBoolean("has_subject"),
                            rs.getString("result"), rs.getString("reason"), rs.getString("created_by"),
                            Sql.timestamp(rs, "created_at"), rs.getString("resolved_by"), Sql.timestamp(rs, "resolved_at"),
                            rs.getString("note")));
                }
            }
            return out;
        });
    }

    @RequiresRole(Role.OWNER)
    @Post("/api/privacy-requests/{id}/report")
    public Report report(@PathVariable long id) {
        var operator = current.id();
        return db.inTx(c -> {
            String customerId;
            String email;
            String phone;
            String externalRef;
            String[] orders;
            try (var ps = Sql.prepare(c, """
                    SELECT kind, status, external_ref, subject->>'customer_id', subject->>'email', subject->>'phone',
                           ARRAY(SELECT jsonb_array_elements_text(COALESCE(subject->'orders', '[]'::jsonb)))
                      FROM privacy_requests WHERE id = ?""", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no privacy request " + id);
                if (!rs.getString(1).equals("data_request")) throw Problems.badRequest("only a data request has a report");
                if (!rs.getString(2).equals("open")) throw Problems.conflict("already-answered", "privacy request " + id + " is done; its identifiers are gone");
                externalRef = rs.getString(3);
                customerId = rs.getString(4);
                email = rs.getString(5);
                phone = rs.getString(6);
                orders = (String[]) rs.getArray(7).getArray();
            }
            var people = new ArrayList<Person>();
            for (var identity : roots(c, erasure.identitiesFor(c, customerId, email, phone))) {
                pii.record(c, operator, identity, "data_request", "privacy request " + id);
                people.add(person(c, identity));
            }
            return new Report(id, externalRef, OffsetDateTime.now(), held(), people, orders(c,
                    "SELECT order_number, total_paise, phone, email, created_at FROM orders WHERE id = ANY (CAST(? AS text[])) ORDER BY created_at",
                    orders));
        });
    }

    /** What the report's sections mean, for the store owner who forwards it. */
    static String held() {
        return "Engage, WUMIKA's messaging system, holds: identifiers (phone, email, Shopify customer id, browser and cart ids); "
                + "profile (first name, city, tags); consent history for WhatsApp, SMS, push and email; push devices; "
                + "messages sent; orders (number, total, contact); size alerts. Orders themselves are kept by Shopify.";
    }

    /** Merged-away identities point at their survivor; report each person once. */
    private static List<UUID> roots(Connection c, java.util.Set<UUID> ids) throws SQLException {
        var out = new java.util.LinkedHashSet<UUID>();
        for (var id : ids) {
            try (var ps = Sql.prepare(c, """
                    WITH RECURSIVE up AS (SELECT id, merged_into, 0 AS d FROM identities WHERE id = ?
                                          UNION ALL SELECT i.id, i.merged_into, up.d + 1 FROM identities i JOIN up ON i.id = up.merged_into WHERE up.d < 50)
                    SELECT id FROM up WHERE merged_into IS NULL LIMIT 1""", id);
                 var rs = ps.executeQuery()) {
                out.add(rs.next() ? rs.getObject(1, UUID.class) : id);
            }
        }
        return new ArrayList<>(out);
    }

    private static Person person(Connection c, UUID id) throws SQLException {
        String profile = "{}";
        try (var ps = Sql.prepare(c, "SELECT attrs::text FROM profiles WHERE identity_id = ?", id); var rs = ps.executeQuery()) {
            if (rs.next()) profile = rs.getString(1);
        }
        var keys = new ArrayList<Key>();
        try (var ps = Sql.prepare(c, """
                SELECT kind::text, value, verified, first_seen FROM identity_keys
                 WHERE identity_id = ? AND kind NOT IN ('fcm_token') ORDER BY kind, first_seen""", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) keys.add(new Key(rs.getString(1), rs.getString(2), rs.getBoolean(3), Sql.timestamp(rs, "first_seen")));
        }
        var consents = new ArrayList<Consent>();
        try (var ps = Sql.prepare(c, """
                SELECT channel::text, purpose::text, state::text, source, occurred_at FROM consents
                 WHERE identity_id = ? ORDER BY occurred_at""", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) consents.add(new Consent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Sql.timestamp(rs, "occurred_at")));
        }
        var devices = new ArrayList<Device>();
        try (var ps = Sql.prepare(c, "SELECT platform, browser, active, created_at FROM devices WHERE identity_id = ? ORDER BY created_at", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) devices.add(new Device(rs.getString(1), rs.getString(2), rs.getBoolean(3), Sql.timestamp(rs, "created_at")));
        }
        var messages = new ArrayList<Message>();
        try (var ps = Sql.prepare(c, """
                SELECT channel::text, template_key, status::text, created_at FROM sends
                 WHERE identity_id = ? AND status NOT IN ('blocked', 'deferred') ORDER BY created_at DESC LIMIT 1000""", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) messages.add(new Message(rs.getString(1), rs.getString(2), rs.getString(3), Sql.timestamp(rs, "created_at")));
        }
        var orders = orders(c, "SELECT order_number, total_paise, phone, email, created_at FROM orders WHERE identity_id = ? ORDER BY created_at", id);
        var waitlist = new ArrayList<String>();
        try (var ps = Sql.prepare(c, "SELECT product_handle || COALESCE(' (' || size_label || ')', '') FROM stock_waitlist WHERE identity_id = ?", id);
             var rs = ps.executeQuery()) {
            while (rs.next()) waitlist.add(rs.getString(1));
        }
        return new Person(profile, keys, consents, devices, messages, orders, waitlist);
    }

    private static List<Order> orders(Connection c, String sql, Object param) throws SQLException {
        var out = new ArrayList<Order>();
        try (var ps = Sql.prepare(c, sql, param); var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Order(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), Sql.timestamp(rs, "created_at")));
            }
        }
        return out;
    }

    @RequiresRole(Role.OWNER)
    @Post("/api/privacy-requests/{id}/resolve")
    public PrivacyRequest resolve(@PathVariable long id, @Nullable @Body Resolve body) {
        var operator = current.id();
        var note = body == null || body.note() == null || body.note().isBlank() ? null : body.note().strip();
        if (note != null && note.length() > 1000) throw Problems.badRequest("note is at most 1000 characters");
        db.inTx(c -> {
            String kind;
            try (var ps = Sql.prepare(c, "SELECT kind, status FROM privacy_requests WHERE id = ?", id); var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no privacy request " + id);
                if (rs.getString(2).equals("done")) throw Problems.conflict("already-resolved", "privacy request " + id + " is already done");
                kind = rs.getString(1);
            }
            Sql.update(c, """
                    UPDATE privacy_requests SET status = 'done', subject = '{}', note = ?, resolved_by = ?, resolved_at = now()
                     WHERE id = ?""", note, operator, id);
            audit.record(c, operator, "privacy.resolve", "privacy_request", Long.toString(id), null,
                    "{\"kind\": \"" + kind + "\"}");
            return null;
        });
        return list("done").stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> Problems.notFound("no privacy request " + id));
    }

    @RequiresRole(Role.OWNER)
    @Post("/api/customers/{id}/erase")
    public Erased erase(@PathVariable UUID id, @Nullable @Body Erase body) {
        var operator = current.id();
        var reason = body == null || body.reason() == null ? "" : body.reason().strip();
        if (reason.length() < 5) throw Problems.badRequest("a reason of at least 5 characters is required");
        if (reason.length() > 500) throw Problems.badRequest("reason is at most 500 characters");
        if (id.equals(Erasure.TOMBSTONE)) throw Problems.badRequest("not a customer");
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT 1 FROM identities WHERE id = ?", id); var rs = ps.executeQuery()) {
                if (!rs.next()) throw Problems.notFound("no customer " + id);
            }
            long requestId;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO privacy_requests (kind, source, reason, created_by) VALUES ('erase', 'console', ?, ?)
                    RETURNING id""", reason, operator);
                 var rs = ps.executeQuery()) {
                rs.next();
                requestId = rs.getLong(1);
            }
            var result = erasure.erase(c, id, "privacy_request:" + requestId, operator);
            Sql.update(c, """
                    UPDATE privacy_requests SET status = 'done', result = CAST(? AS jsonb), resolved_by = ?, resolved_at = now()
                     WHERE id = ?""", result, operator, requestId);
            return new Erased(requestId, result);
        });
    }
}
