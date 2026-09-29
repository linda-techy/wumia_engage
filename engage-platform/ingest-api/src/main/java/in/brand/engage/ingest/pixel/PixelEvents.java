package in.brand.engage.ingest.pixel;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/**
 * Stores web pixel events as timing hints (phase-2 §7).
 *
 * <p>Every event goes to {@code events} with {@code source = 'pixel'} and no
 * identity: the pixel never creates or merges identities. The browser's
 * {@code anon_id} is kept in the props so a consumer can find an identity
 * that the push embed <em>already</em> linked to it, and nothing more.
 * A checkout step also moves {@code checkouts.last_step} forward for that
 * token, when the checkout webhook has already created the row.
 */
@Singleton
public class PixelEvents {

    private final Db db;
    private final EventWriter events;

    public PixelEvents(Db db, EventWriter events) {
        this.db = db;
        this.events = events;
    }

    public void record(PixelEvent.Valid e) {
        var at = e.at().atOffset(ZoneOffset.UTC);
        db.inTx(c -> {
            var props = new LinkedHashMap<String, Object>();
            props.put("client_id", e.clientId());
            props.put("anon_id", e.anonId());
            props.put("at", e.at().toString());
            props.put("checkout_token", e.checkoutToken());
            props.put("total_paise", e.totalPaise());
            props.put("product_id", e.productId());
            props.put("variant_id", e.variantId());
            props.put("product_title", e.productTitle());
            props.put("product_handle", e.productHandle());
            props.put("price_paise", e.pricePaise());
            events.write(c, null, e.name(), "pixel", "pixel:" + e.clientId() + ":" + e.name() + ":" + e.at(), props);

            if (e.isCheckoutStep()) {
                // Latest step by the pixel's clock, so a late or replayed event cannot move it back.
                Sql.update(c, """
                        UPDATE checkouts SET last_step = ?, last_step_at = ?
                         WHERE token = ? AND (last_step_at IS NULL OR last_step_at < ?)""",
                        e.name(), at, e.checkoutToken(), at);
            }
            return null;
        });
    }
}
