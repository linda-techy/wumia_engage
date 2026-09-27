package in.brand.engage.ingest.razorpay;

import in.brand.engage.core.identity.Msisdn;
import in.brand.engage.core.razorpay.FailureKind;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.IdentityResolver;
import in.brand.engage.persistence.IdentityResolver.Key;
import in.brand.engage.ingest.inbox.InboxHandler;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Turns Razorpay payment webhooks into {@code payment_attempts} rows, matched
 * to the Shopify checkout they belong to, plus one event each.
 *
 * <p>{@code payment_failed_confirmed} is the trigger for the UTILITY
 * {@code payment_failed} journey (Phase 5 §2.1): a real, gateway-confirmed
 * payment attempt. Without a row here, a stalled checkout is an abandoned
 * checkout — MARKETING — and must not claim a failure.
 */
@Singleton
public class RazorpayInboxHandler implements InboxHandler {

    private final IdentityResolver identities;
    private final EventWriter events;
    private final int matchWindowMinutes;

    public RazorpayInboxHandler(IdentityResolver identities, EventWriter events,
                                EngageProperties.Razorpay razorpay) {
        this.identities = identities;
        this.events = events;
        this.matchWindowMinutes = razorpay.matchWindowMinutes() > 0 ? razorpay.matchWindowMinutes() : 30;
    }

    @Override
    public String source() {
        return "razorpay";
    }

    record Payment(String event, String paymentId, String gatewayOrderId, String status, long amountPaise,
                   String currency, String method, String contactRaw, String email, String notesJson,
                   String errorCode, String errorDescription, String errorSource, String errorStep,
                   String errorReason, OffsetDateTime createdAt) {}

    @Override
    public void handle(Connection c, InboxRepository.Item item) throws SQLException {
        var eventName = switch (item.topic()) {
            case "payment.failed" -> "payment_failed_confirmed";
            case "payment.captured" -> "payment_captured";
            case "payment.authorized" -> "payment_authorized";
            default -> null;
        };
        if (eventName == null) return;   // not subscribed / not relevant: mark processed, do nothing

        var p = load(c, item.deliveryId());
        var phone = Msisdn.normalise(p.contactRaw()).orElse(null);

        // The payer's own contact details: BUYER trust (may absorb a soft identity,
        // never a verified customer's). No strong key here, so nothing merges.
        var keys = new ArrayList<Key>();
        keys.add(Key.buyer("phone", phone));
        keys.add(Key.buyer("email", p.email()));
        UUID identityId = identities.resolve(c, keys);

        String checkoutToken = null;
        String matchMethod = "none";
        try (var ps = Sql.prepare(c, SqlFiles.get("razorpay_match_checkout.sql"),
                p.notesJson(), p.notesJson(), phone, p.amountPaise(), p.createdAt(), matchWindowMinutes,
                p.createdAt());
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                checkoutToken = rs.getString("token");
                matchMethod = rs.getString("method");
            }
        }

        Sql.update(c, """
                INSERT INTO payment_attempts
                  (gateway, gateway_payment_id, gateway_order_id, status, amount_paise, currency, method,
                   contact_raw, phone, email, notes, error_code, error_description, error_source, error_step,
                   error_reason, identity_id, checkout_token, match_method, gateway_created_at)
                VALUES ('razorpay', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (gateway, gateway_payment_id, status) DO NOTHING""",
                p.paymentId(), p.gatewayOrderId(), p.status(), p.amountPaise(), p.currency(), p.method(),
                p.contactRaw(), phone, p.email(), p.notesJson(), p.errorCode(), p.errorDescription(),
                p.errorSource(), p.errorStep(), p.errorReason(), identityId, checkoutToken, matchMethod,
                p.createdAt());

        var props = new LinkedHashMap<String, Object>();
        props.put("payment_id", p.paymentId());
        props.put("amount_paise", p.amountPaise());
        props.put("method", p.method());
        props.put("checkout_token", checkoutToken);
        props.put("match_method", matchMethod);
        if (item.topic().equals("payment.failed")) {
            props.put("failure_kind", FailureKind.classify(p.errorSource(), p.errorReason()).name());
            props.put("error_source", p.errorSource());
            props.put("error_reason", p.errorReason());
        }
        events.write(c, identityId, eventName, "razorpay",
                "razorpay:" + p.paymentId() + ":" + p.status(), props);
    }

    private static Payment load(Connection c, String deliveryId) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("razorpay_payment.sql"), deliveryId);
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("inbox row vanished: " + deliveryId);
            return new Payment(
                    rs.getString("event"), rs.getString("payment_id"), rs.getString("gateway_order_id"),
                    rs.getString("status"), rs.getLong("amount_paise"), rs.getString("currency"),
                    rs.getString("method"), rs.getString("contact_raw"), rs.getString("email"),
                    rs.getString("notes_json"), rs.getString("error_code"), rs.getString("error_description"),
                    rs.getString("error_source"), rs.getString("error_step"), rs.getString("error_reason"),
                    Sql.timestamp(rs, "gateway_created_at"));
        }
    }
}
