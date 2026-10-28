package in.brand.engage.ingest.wa;

import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.IdentityResolver;
import in.brand.engage.persistence.IdentityResolver.Key;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storefront's "Get my code on WhatsApp" (V54). The browser asks for a
 * one-time ref; the shopper's own WhatsApp message carries it back, which
 * proves the number (WhatsApp sent it), is their opt-in (they wrote first,
 * under the registered wording wa_offer_v1), and ties the number to their
 * browsing session, so a later abandoned cart or checkout can be recovered on
 * WhatsApp. The worker answers with the code (a free reply in the window the
 * message opened).
 */
@Singleton
public class WaOffers {

    private static final Logger LOG = LoggerFactory.getLogger(WaOffers.class);

    /** No 0/O or 1/I/L: the ref is read in a chat, and never looks like an order number. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final Pattern REF = Pattern.compile("\\(ref ([" + ALPHABET + "]{6})\\)");
    static final String COPY_VERSION = "wa_offer_v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityResolver identities;
    private final ConsentWriter consents;

    public WaOffers(IdentityResolver identities, ConsentWriter consents) {
        this.identities = identities;
        this.consents = consents;
    }

    /**
     * What the popup needs: the ref, the number, the message the shopper sends
     * and the wording under the button. Empty when no WhatsApp number is set.
     * The same unused ref comes back within a day, so a shopper who sees the
     * popup twice does not collect refs.
     */
    public java.util.Optional<Map<String, Object>> link(Connection c, String anonId, String cartToken) throws SQLException {
        var number = text(config(c, "whatsapp.display_number"));
        number = number == null ? "" : number.replaceAll("\\D", "");
        if (number.length() < 10) return java.util.Optional.empty();
        String ref = null;
        try (var ps = Sql.prepare(c, """
                SELECT ref FROM wa_link_tokens WHERE anon_id = ? AND used_at IS NULL AND created_at > now() - interval '1 day'
                 ORDER BY created_at DESC LIMIT 1""", anonId); var rs = ps.executeQuery()) {
            if (rs.next()) ref = rs.getString(1);
        }
        for (int attempt = 0; ref == null && attempt < 5; attempt++) {
            var candidate = newRef();
            if (Sql.update(c, "INSERT INTO wa_link_tokens (ref, anon_id, cart_token) VALUES (?, ?, ?) ON CONFLICT (ref) DO NOTHING",
                    candidate, anonId, cartToken) == 1) {
                ref = candidate;
            }
        }
        if (ref == null) throw new IllegalStateException("could not mint a WhatsApp ref");
        var label = text(config(c, "journey.welcome.discount_label"));
        var code = text(config(c, "journey.welcome.discount_code"));
        var offer = code == null || code.isBlank() || label == null || label.isBlank() ? null : label.strip();
        var out = new LinkedHashMap<String, Object>();
        out.put("ref", ref);
        out.put("number", number);
        out.put("offer", offer);
        out.put("message", offer != null
                ? "Hi WUMIKA! Please send my " + offer + " code. (ref " + ref + ")"
                : "Hi WUMIKA! Please send me order updates and new drops on WhatsApp. (ref " + ref + ")");
        out.put("copy", copyText(c));
        out.put("copyVersion", COPY_VERSION);
        return java.util.Optional.of(out);
    }

    /**
     * An inbound WhatsApp message carrying a ref: the opt-in, and the link to
     * the browsing session when the ref is fresh and unused.
     *
     * @return the customer (after any merge), or null when the message is not a code request
     */
    public UUID claim(Connection c, UUID identity, String msisdn, String waId, String text, String messageId, Instant at)
            throws SQLException {
        if (text == null) return null;
        var m = REF.matcher(text);
        if (!m.find()) return null;
        var ref = m.group(1);
        String anon = null;
        String cart = null;
        try (var ps = Sql.prepare(c, """
                UPDATE wa_link_tokens SET used_at = now()
                 WHERE ref = ? AND used_at IS NULL AND created_at > now() - interval '7 days'
                 RETURNING anon_id, cart_token""", ref); var rs = ps.executeQuery()) {
            if (rs.next()) {
                anon = rs.getString(1);
                cart = rs.getString(2);
            }
        }
        var who = identity;
        if (anon != null) {
            // WhatsApp proved the number and the ref proves the browser: one person.
            who = identities.resolve(c, List.of(Key.verified("phone", msisdn), Key.verified("wa_id", waId), Key.session("anon", anon)));
            if (cart != null) Sql.update(c, "UPDATE carts SET identity_id = ? WHERE cart_token = ? AND identity_id IS NULL", who, cart);
            Sql.update(c, "UPDATE wa_link_tokens SET used_by = ? WHERE ref = ?", who, ref);
            // The popup funnel's last step: the shopper's message arrived (dashboard "Token" column for wa_offer).
            Sql.update(c, """
                    INSERT INTO push_prompt_events (anon_id, identity_id, surface, step, platform) VALUES (?, ?, 'wa_offer', 'token_minted', 'WEB')""",
                    anon, who);
        } else {
            LOG.info("WhatsApp code request with an unknown, used or expired ref; opt-in kept, browser not linked");
        }
        consents.statement(c, who, "granted", "wa_offer_link", COPY_VERSION, "wa_offer:" + messageId, at, List.of(
                new String[] {"whatsapp", "transactional"}, new String[] {"whatsapp", "marketing"}));
        return who;
    }

    static String newRef() {
        var sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static String copyText(Connection c) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT text FROM consent_copy_versions WHERE version = ?", COPY_VERSION); var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The global value as JSON text, or the key's default. */
    private static String config(Connection c, String key) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE((SELECT value::text FROM config_current WHERE key = ? AND selector = '*' LIMIT 1),
                                (SELECT default_value::text FROM config_keys WHERE key = ?))""", key, key);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A JSON string value without its quotes. */
    private static String text(String json) {
        if (json == null) return null;
        var s = json.strip();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1).replace("\\\"", "\"") : s;
    }
}
