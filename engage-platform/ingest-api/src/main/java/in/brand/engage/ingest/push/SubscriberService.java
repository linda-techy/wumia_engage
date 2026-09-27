package in.brand.engage.ingest.push;

import in.brand.engage.core.privacy.CustomerAllowlist;
import in.brand.engage.core.shopify.AppProxyVerifier.ProxyContext;
import in.brand.engage.core.shopify.CartTokens;
import in.brand.engage.ingest.db.EventWriter;
import in.brand.engage.ingest.db.IdentityResolver;
import in.brand.engage.ingest.db.IdentityResolver.Key;
import in.brand.engage.ingest.push.StorefrontRequests.Cart;
import in.brand.engage.ingest.push.StorefrontRequests.NotifyMe;
import in.brand.engage.ingest.push.StorefrontRequests.PromptEvent;
import in.brand.engage.ingest.push.StorefrontRequests.Refresh;
import in.brand.engage.ingest.push.StorefrontRequests.Register;
import in.brand.engage.ingest.push.StorefrontRequests.Unregister;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storefront push subscribers (phase-2 §8, §9): binds a browser's FCM token to
 * an identity and records push consent in the append-only ledger.
 *
 * <p><b>Who can be stored.</b> The customer allowlist applies here as it does to
 * webhooks: while it is restricted, only a signed-in customer whose email is
 * allowlisted is written. Anonymous browsers and everyone else are
 * acknowledged ({@link Outcome#FILTERED}) and nothing is written — not the
 * identity, not the token. Prompt-funnel events carry no personal data and are
 * always recorded, without an identity.
 *
 * <p><b>Whose device it is.</b> Only the App Proxy's signed
 * {@code logged_in_customer_id} names a customer. The anon id, token and cart
 * token in the body are session keys: they follow the signed customer, and
 * never choose one (see resolve_identity, V6).
 *
 * <p><b>What was consented to.</b> A grant covers the registered copy version
 * the shopper saw. An unregistered version, or wording that differs from the
 * registered text, grants nothing and stores nothing ({@link Outcome#UNKNOWN_COPY}).
 */
@Singleton
public class SubscriberService {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriberService.class);

    public enum Outcome { STORED, FILTERED, UNKNOWN_COPY }

    public record Registered(Outcome outcome, Long deviceId) {}

    private final Db db;
    private final IdentityResolver identities;
    private final EventWriter events;
    private final CustomerAllowlist allowlist;

    public SubscriberService(Db db, IdentityResolver identities, EventWriter events, CustomerAllowlist allowlist) {
        this.db = db;
        this.identities = identities;
        this.events = events;
        this.allowlist = allowlist;
    }

    /* ------------------------------ register ----------------------------- */

    public Registered register(ProxyContext ctx, Register r, String origin, String userAgent) {
        return db.inTx(c -> {
            var copy = registeredPushCopy(c, r.copyVersion());
            if (copy == null || !copy.equals(r.copyText())) {
                LOG.warn("push register refused: copy version '{}' is {} — register the exact wording first",
                        r.copyVersion(), copy == null ? "not registered" : "registered with different text");
                return new Registered(Outcome.UNKNOWN_COPY, null);
            }
            if (!mayStore(c, ctx)) return new Registered(Outcome.FILTERED, null);

            var keys = sessionKeys(ctx, r.anonId());
            keys.add(Key.session("fcm_token", r.token()));
            if (r.cartToken() != null) keys.add(Key.session("cart_token", CartTokens.normalise(r.cartToken())));
            var identityId = identities.resolve(c, keys);

            long deviceId;
            try (var ps = Sql.prepare(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (fcm_token) DO UPDATE
                       SET identity_id = EXCLUDED.identity_id, platform = EXCLUDED.platform,
                           browser = COALESCE(EXCLUDED.browser, devices.browser),
                           permission_source = EXCLUDED.permission_source,
                           consent_copy_ver = EXCLUDED.consent_copy_ver,
                           active = true, deactivated_reason = NULL, last_refreshed_at = now()
                    RETURNING id""",
                    identityId, r.token(), r.platform(), r.browser(), origin, r.surface(), r.copyVersion());
                 var rs = ps.executeQuery()) {
                rs.next();
                deviceId = rs.getLong(1);
            }

            // One row per purpose the copy declares, skipped when the same grant
            // under the same copy is already the state in force (re-registering
            // on every visit must not bloat the ledger).
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, copy_version, evidence)
                    SELECT ?, 'push', pur, 'granted', ?, v.version,
                           jsonb_strip_nulls(jsonb_build_object(
                             'copy_text', v.text, 'page', ?::text, 'surface', ?::text,
                             'platform', ?::text, 'browser', ?::text, 'user_agent', ?::text,
                             'device_id', ?::bigint, 'anon_id', ?::text, 'pre_ticked', false))
                      FROM consent_copy_versions v
                     CROSS JOIN LATERAL unnest(v.purposes) AS pur
                      LEFT JOIN LATERAL (SELECT x.state, x.copy_version FROM consents x
                                          WHERE x.identity_id = ? AND x.channel = 'push' AND x.purpose = pur
                                          ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1) cur ON true
                     WHERE v.version = ? AND v.channel = 'push'
                       AND (cur.state IS DISTINCT FROM 'granted' OR cur.copy_version IS DISTINCT FROM v.version)""",
                    identityId, "soft_ask:" + r.surface(), r.page(), r.surface(), r.platform(), r.browser(), userAgent,
                    deviceId, r.anonId(), identityId, r.copyVersion());

            events.write(c, identityId, "push_subscribed", "storefront",
                    "push_subscribed:" + deviceId + ":" + r.copyVersion(),
                    Map.of("device_id", deviceId, "surface", r.surface(), "platform", r.platform()));
            return new Registered(Outcome.STORED, deviceId);
        });
    }

    /* ------------------------------- refresh ----------------------------- */

    /**
     * Weekly client refresh. A changed token inherits the old device's consent
     * context and the old one is deactivated as {@code rotated}; rotation is not
     * a new consent. An unknown token is not created here: only /register,
     * which carries the copy the shopper agreed to, can create a device.
     */
    public Outcome refresh(ProxyContext ctx, Refresh r) {
        return db.inTx(c -> {
            if (!mayStore(c, ctx)) return Outcome.FILTERED;
            var keys = sessionKeys(ctx, r.anonId());
            keys.add(Key.session("fcm_token", r.token()));
            var identityId = identities.resolve(c, keys);

            if (r.previous() != null) {
                int n = Sql.update(c, """
                        INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, sw_version,
                                             permission_source, consent_copy_ver)
                        SELECT identity_id, ?, platform, browser, origin, sw_version, permission_source, consent_copy_ver
                          FROM devices WHERE fcm_token = ? AND identity_id = ?
                        ON CONFLICT (fcm_token) DO UPDATE
                           SET active = true, deactivated_reason = NULL, last_refreshed_at = now()
                         WHERE devices.identity_id = EXCLUDED.identity_id""",
                        r.token(), r.previous(), identityId);
                if (n > 0) {
                    Sql.update(c, """
                            UPDATE devices SET active = false, deactivated_reason = 'rotated'
                             WHERE fcm_token = ? AND identity_id = ? AND active""", r.previous(), identityId);
                    return Outcome.STORED;
                }
            }
            int touched = Sql.update(c, """
                    UPDATE devices SET last_refreshed_at = now()
                     WHERE fcm_token = ? AND identity_id = ? AND active""", r.token(), identityId);
            return touched > 0 ? Outcome.STORED : Outcome.FILTERED;
        });
    }

    /* ------------------------------ unregister --------------------------- */

    /** "Turn off" in-page: deactivate this browser and withdraw push consent. */
    public Outcome unregister(ProxyContext ctx, Unregister r) {
        return db.inTx(c -> {
            if (!mayStore(c, ctx)) return Outcome.FILTERED;
            var identityId = identities.resolve(c, sessionKeys(ctx, r.anonId()));
            Sql.update(c, """
                    UPDATE devices SET active = false, deactivated_reason = 'user_off'
                     WHERE fcm_token = ? AND identity_id = ? AND active""", r.token(), identityId);
            Sql.update(c, """
                    INSERT INTO consents (identity_id, channel, purpose, state, source, evidence)
                    SELECT identity_id, 'push', purpose, 'withdrawn', 'push_off:in_page',
                           jsonb_build_object('anon_id', ?::text)
                      FROM consent_current
                     WHERE identity_id = ? AND channel = 'push' AND state = 'granted'""", r.anonId(), identityId);
            return Outcome.STORED;
        });
    }

    /* --------------------------- funnel, cart, waitlist ------------------- */

    /** Pseudonymous funnel metric: never resolves or creates an identity. */
    public void promptEvent(PromptEvent e) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO push_prompt_events (anon_id, surface, step, browser, platform)
                VALUES (?, ?, ?, ?, ?)""", e.anonId(), e.surface(), e.step(), e.browser(), e.platform()));
    }

    public Outcome cart(ProxyContext ctx, Cart r) {
        var cartToken = CartTokens.normalise(r.cartToken());
        return db.inTx(c -> {
            if (!mayStore(c, ctx)) return Outcome.FILTERED;
            var keys = sessionKeys(ctx, r.anonId());
            keys.add(Key.session("cart_token", cartToken));
            var identityId = identities.resolve(c, keys);
            Sql.update(c, "UPDATE carts SET identity_id = ? WHERE cart_token = ? AND identity_id IS NULL",
                    identityId, cartToken);
            return Outcome.STORED;
        });
    }

    public Outcome notifyMe(ProxyContext ctx, NotifyMe r) {
        return db.inTx(c -> {
            if (!mayStore(c, ctx)) return Outcome.FILTERED;
            var identityId = identities.resolve(c, sessionKeys(ctx, r.anonId()));
            Sql.update(c, """
                    INSERT INTO stock_waitlist (identity_id, variant_id, product_handle, size_label)
                    VALUES (?, ?, ?, ?) ON CONFLICT (identity_id, variant_id) DO NOTHING""",
                    identityId, r.variantId(), r.productHandle(), r.sizeLabel());
            return Outcome.STORED;
        });
    }

    /* ------------------------------- helpers ----------------------------- */

    /**
     * The allowlist, decided before anything is written. With a restricted list
     * the only attributable shopper is a signed-in customer whose email (from
     * a customers/* or order webhook) is on it.
     */
    private boolean mayStore(Connection c, ProxyContext ctx) throws SQLException {
        if (allowlist.allowAll()) return true;
        var customerId = ctx.loggedInCustomerId();
        if (customerId.isEmpty()) return false;
        try (var ps = Sql.prepare(c, """
                SELECT EXISTS (SELECT 1 FROM identity_keys sc
                                 JOIN identity_keys e ON e.identity_id = sc.identity_id
                                WHERE sc.kind = 'shopify_customer' AND sc.value = ?
                                  AND e.kind = 'email' AND e.value = ANY (?))""",
                customerId.get(), allowlist.emails().toArray(String[]::new));
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String registeredPushCopy(Connection c, String version) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT text FROM consent_copy_versions WHERE version = ? AND channel = 'push'", version);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The signed customer (STRONG) plus this browser's anon id (SESSION). */
    private static List<Key> sessionKeys(ProxyContext ctx, String anonId) {
        var keys = new ArrayList<Key>();
        ctx.loggedInCustomerId().ifPresent(id -> keys.add(Key.verified("shopify_customer", id)));
        keys.add(Key.session("anon", anonId));
        return keys;
    }
}
