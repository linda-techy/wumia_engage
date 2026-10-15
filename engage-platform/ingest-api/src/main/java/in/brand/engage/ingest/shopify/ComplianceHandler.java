package in.brand.engage.ingest.shopify;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Erasure;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shopify's mandatory compliance webhooks (P7-T05), through the same HMAC
 * check and inbox as every other topic.
 *
 * <ul>
 * <li>{@code customers/redact}: erased at once ({@link Erasure}): the people
 *     with that customer id, email or phone, and the listed orders. Recorded as
 *     a done privacy request with counts only.</li>
 * <li>{@code customers/data_request}: an open privacy request for the Owner,
 *     who downloads the report from the console and sends it to the store
 *     owner within 30 days.</li>
 * <li>{@code shop/redact}: the store uninstalled the app 48 hours ago. An open
 *     request and an ERROR: wiping everything is a manual runbook step
 *     (08-deployment-and-ops.md, "Shop redact"), never automatic.</li>
 * </ul>
 */
@Singleton
public class ComplianceHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceHandler.class);

    private final Erasure erasure;

    public ComplianceHandler(Erasure erasure) {
        this.erasure = erasure;
    }

    record Payload(String customerId, String email, String phone, List<String> orders, String dataRequestId) {}

    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        var source = "shopify:" + item.deliveryId();
        switch (item.topic()) {
            case "customers/redact" -> redact(c, source, payload(c, item));
            case "customers/data_request" -> {
                var p = payload(c, item);
                long id = insert(c, "data_request", source, p.dataRequestId(), p);
                LOG.warn("Shopify data request {} (privacy request {}): answer within 30 days from Privacy requests in the console",
                        p.dataRequestId(), id);
            }
            case "shop/redact" -> {
                long id = insert(c, "shop_redact", source, null, null);
                LOG.error("Shopify shop/redact (privacy request {}): the store uninstalled the app 48 h ago and asks for "
                        + "all its data to be deleted; follow the runbook (08-deployment-and-ops.md, Shop redact)", id);
            }
            default -> throw new IllegalArgumentException("not a compliance topic: " + item.topic());
        }
    }

    private void redact(Connection c, String source, Payload p) throws SQLException {
        long id = insert(c, "customer_redact", source, null, null);
        var ref = "privacy_request:" + id;
        var results = new ArrayList<String>();
        for (var identity : erasure.identitiesFor(c, p.customerId(), p.email(), p.phone())) {
            results.add(erasure.erase(c, identity, ref, null));
        }
        int orders = erasure.scrubOrders(c, p.orders(), ref);
        Sql.update(c, """
                UPDATE privacy_requests
                   SET status = 'done', resolved_at = now(),
                       result = jsonb_build_object('people', CAST(? AS int), 'erasures', CAST(? AS jsonb), 'orders_scrubbed', CAST(? AS int))
                 WHERE id = ?""", results.size(), "[" + String.join(",", results) + "]", orders, id);
        LOG.info("Shopify customers/redact: privacy request {} erased {} identity group(s), scrubbed {} listed order(s)",
                id, results.size(), orders);
    }

    private static long insert(Connection c, String kind, String source, String externalRef, Payload subject) throws SQLException {
        try (var ps = Sql.prepare(c, """
                INSERT INTO privacy_requests (kind, source, external_ref, subject)
                VALUES (?, ?, ?, CASE WHEN CAST(? AS boolean) THEN jsonb_strip_nulls(jsonb_build_object(
                          'customer_id', CAST(? AS text), 'email', CAST(? AS text), 'phone', CAST(? AS text),
                          'orders', to_jsonb(CAST(? AS text[])))) ELSE '{}' END)
                RETURNING id""",
                kind, source, externalRef, subject != null,
                subject == null ? null : subject.customerId(), subject == null ? null : subject.email(),
                subject == null ? null : subject.phone(),
                subject == null ? new String[0] : subject.orders().toArray(String[]::new));
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Read before anything is erased: the erasure scrubs this very payload. */
    private static Payload payload(Connection c, InboxRepository.Item item) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT payload #>> '{customer,id}' AS customer_id,
                       lower(NULLIF(btrim(payload #>> '{customer,email}'), '')) AS email,
                       payload #>> '{customer,phone}' AS phone,
                       ARRAY(SELECT jsonb_array_elements_text(COALESCE(payload -> 'orders_to_redact',
                                                                         payload -> 'orders_requested', '[]'::jsonb))) AS orders,
                       payload #>> '{data_request,id}' AS data_request_id
                  FROM webhook_inbox WHERE source = 'shopify' AND delivery_id = ?""", item.deliveryId());
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("inbox row vanished: " + item.deliveryId());
            var orders = rs.getArray("orders");
            return new Payload(rs.getString("customer_id"), rs.getString("email"),
                    Msisdn.normalise(rs.getString("phone")).orElse(null),
                    orders == null ? List.of() : Arrays.asList((String[]) orders.getArray()),
                    rs.getString("data_request_id"));
        }
    }
}
