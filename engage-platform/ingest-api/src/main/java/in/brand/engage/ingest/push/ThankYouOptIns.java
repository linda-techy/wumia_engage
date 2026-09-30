package in.brand.engage.ingest.push;

import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * Thank you page WhatsApp opt-ins (phase-2 §6.2, P2-T05).
 *
 * <p>The page can render before {@code orders/create} arrives, so every
 * opt-in is first stored as pending, keyed by order id. If the order is
 * already here with a phone, it is applied at once; otherwise the order
 * webhook applies it when it lands ({@code ShopifyInboxHandler}). The phone
 * always comes from the order, never from the request.
 *
 * <p>Both paths take the same per-order lock ({@link ConsentWriter#lockOrder}),
 * so an opt-in racing the order webhook cannot fall between them.
 */
@Singleton
public class ThankYouOptIns {

    public enum Outcome { APPLIED, PENDING, UNKNOWN_COPY }

    private final Db db;
    private final ConsentWriter consents;

    public ThankYouOptIns(Db db, ConsentWriter consents) {
        this.db = db;
        this.consents = consents;
    }

    public Outcome record(String orderId, String shop, String copyVersion) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT 1 FROM consent_copy_versions
                     WHERE version = ? AND channel = 'whatsapp' AND surface = 'thank_you'""", copyVersion);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return Outcome.UNKNOWN_COPY;
            }
            ConsentWriter.lockOrder(c, orderId);
            Sql.update(c, """
                    INSERT INTO pending_optins (order_id, shop, channel, copy_version)
                    VALUES (?, ?, 'whatsapp', ?) ON CONFLICT (order_id) DO NOTHING""", orderId, shop, copyVersion);

            try (var ps = Sql.prepare(c, "SELECT identity_id, phone FROM orders WHERE id = ?", orderId);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return Outcome.PENDING;
                var identityId = rs.getObject("identity_id", UUID.class);
                if (identityId == null || rs.getString("phone") == null) return Outcome.PENDING;
                consents.applyPendingOptIns(c, identityId, orderId);   // a replay applies nothing twice
                return Outcome.APPLIED;
            }
        });
    }
}
