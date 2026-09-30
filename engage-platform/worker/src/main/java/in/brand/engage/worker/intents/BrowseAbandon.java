package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * {@code browse_abandon} (phase-3 §4): three or more product views in one
 * browsing session, no add-to-cart, and the session over (30 minutes idle);
 * a push 4 hours after that. Push only, permanently: the intent is too weak to
 * pay for WhatsApp.
 *
 * <p>Views come from the web pixel (P2-T06), which carries no identity. A
 * browser is reached only when the push embed has <em>already</em> linked its
 * anon id to an identity; nothing here creates or merges one. Each qualifying
 * view restarts the run (cancel the live one, start a new one due 4 h 30 min
 * after this view), so the push goes out 4 h after the last view. An
 * add-to-cart in the pixel, a cart change or an order ends it: cart recovery
 * owns the shopper from there.
 *
 * <p>The subject is the identity: one live browse run per person.
 */
@Singleton
public class BrowseAbandon implements EventConsumer {

    public static final String INTENT = "browse_abandon";
    static final int MIN_VIEWS = 3;
    static final Duration IDLE = Duration.ofMinutes(30);
    static final Duration DELAY = Duration.ofHours(4);
    static final Duration TTL = Duration.ofHours(24);

    private final DefaultOrchestrator orchestrator;
    private final Storefront storefront;

    public BrowseAbandon(DefaultOrchestrator orchestrator, Storefront storefront) {
        this.orchestrator = orchestrator;
        this.storefront = storefront;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition browseAbandon() {
            return new CascadeDefinition(INTENT, Priority.LOW_INTENT,
                    List.of(CascadeDefinition.Step.push("push_browse_abandon_v1", TTL)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return switch (eventName) {
            case "product_viewed", "product_added_to_cart", "cart_updated", "order_placed" -> true;
            default -> false;
        };
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        if (e.name().equals("cart_updated") || e.name().equals("order_placed")) {
            if (e.identityId() != null) orchestrator.cancel(c, INTENT, e.identityId().toString(), "added_to_cart");
            return List.of();
        }

        var identityId = linkedIdentity(c, e.props().get("anon_id"));
        if (identityId == null) return List.of();                 // a browser nobody has linked: skip
        var subject = identityId.toString();
        if (e.name().equals("product_added_to_cart")) {
            orchestrator.cancel(c, INTENT, subject, "added_to_cart");
            return List.of();
        }

        var clientId = e.props().get("client_id");
        if (clientId == null) return List.of();
        var session = session(c, clientId, e.occurredAt());
        if (session == null || session.addedToCart() || session.views() < MIN_VIEWS) return List.of();

        orchestrator.cancel(c, INTENT, subject, "still_browsing");
        var vars = new HashMap<String, String>();
        vars.put("product", session.title() != null ? session.title() : BackInStock.fromHandle(session.handle()));
        vars.put("url", session.variantId() != null
                ? storefront.product(session.handle(), session.variantId())
                : storefront.product(session.handle()));
        return List.of(new MessageIntent(identityId, INTENT, subject, vars, e.occurredAt().plus(IDLE).plus(DELAY)));
    }

    /** Only a key the push embed already stored; SESSION keys never create identities here. */
    private static UUID linkedIdentity(Connection c, String anonId) throws SQLException {
        if (anonId == null || anonId.isBlank()) return null;
        try (var ps = Sql.prepare(c, "SELECT identity_id FROM identity_keys WHERE kind = 'anon' AND value = ?", anonId);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    record Session(int views, boolean addedToCart, String title, String handle, String variantId) {}

    /**
     * The browser's session up to {@code upTo}: its pixel events back to the
     * first gap of 30 minutes or more. Leads with the product viewed most
     * (latest on a tie). Null when the session has no product with a handle.
     */
    static Session session(Connection c, String clientId, Instant upTo) throws SQLException {
        try (var ps = Sql.prepare(c, """
                WITH recent AS (
                  SELECT name, occurred_at, props,
                         lag(occurred_at) OVER (ORDER BY occurred_at DESC, id DESC) AS later
                    FROM events
                   WHERE source = 'pixel' AND props->>'client_id' = ?
                     AND occurred_at <= ? AND occurred_at > CAST(? AS timestamptz) - interval '1 day'
                     AND name IN ('product_viewed', 'product_added_to_cart')),
                gaps AS (
                  SELECT *, sum(CASE WHEN later - occurred_at >= CAST(? AS interval) THEN 1 ELSE 0 END)
                              OVER (ORDER BY occurred_at DESC ROWS UNBOUNDED PRECEDING) AS breaks
                    FROM recent),
                s AS (SELECT * FROM gaps WHERE breaks = 0)
                SELECT (SELECT count(*) FROM s WHERE name = 'product_viewed') AS views,
                       EXISTS (SELECT 1 FROM s WHERE name = 'product_added_to_cart') AS added,
                       top.props->>'product_title' AS title, top.props->>'product_handle' AS handle,
                       top.props->>'variant_id' AS variant_id
                  FROM (SELECT props->>'product_id' AS product_id, count(*) AS n, max(occurred_at) AS last
                          FROM s WHERE name = 'product_viewed' AND props->>'product_handle' IS NOT NULL
                         GROUP BY 1 ORDER BY n DESC, last DESC LIMIT 1) best
                  JOIN LATERAL (SELECT props FROM s
                                 WHERE name = 'product_viewed' AND props->>'product_id' = best.product_id
                                 ORDER BY occurred_at DESC LIMIT 1) top ON true""",
                clientId, upTo.atOffset(java.time.ZoneOffset.UTC), upTo.atOffset(java.time.ZoneOffset.UTC),
                IDLE.toMinutes() + " minutes");
             var rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new Session(rs.getInt("views"), rs.getBoolean("added"), rs.getString("title"),
                    rs.getString("handle"), rs.getString("variant_id"));
        }
    }
}
