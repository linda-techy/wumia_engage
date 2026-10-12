package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.orchestrator.Guard;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.Event;
import in.brand.engage.worker.EventConsumer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code back_in_stock} (phase-3 §4): a restocked size variant alerts the
 * shoppers waiting for it.
 *
 * <p>At most quantity × 20 shoppers, oldest waitlist entry first: telling
 * 2,000 people about 6 units sells out in minutes and disappoints 1,994 of
 * them. The chosen rows are marked notified in the same transaction that
 * starts their intents, so a replay or a second worker cannot alert them
 * twice, and the next restock reaches the next people in line.
 *
 * <p>TTL one hour, urgency high, stale tokens allowed (the shopper asked for
 * this). The subject is {@code <variant>:<identity>}: one live run per
 * person, not one per variant across everyone.
 *
 * <p>P5-T05: a WhatsApp ({@code back_in_stock_v1}) 15 minutes after the push
 * for shoppers who did not click it (a click ends the run), or at once for
 * those the push cannot reach. Marketing opt-in, not INCAPABLE. If the
 * variant sells out again before then ({@code variant_sold_out}), the
 * pending runs end: a message about stock that is gone again disappoints.
 */
@Singleton
public class BackInStock implements EventConsumer {

    public static final String INTENT = "back_in_stock";
    static final int PER_UNIT = 20;
    static final String NO_SIZE_TEMPLATE = "push_back_in_stock_nosize_v1";

    private final Storefront storefront;
    private final DefaultOrchestrator orchestrator;

    public BackInStock(Storefront storefront, DefaultOrchestrator orchestrator) {
        this.storefront = storefront;
        this.orchestrator = orchestrator;
    }

    @Factory
    static class Definition {
        @Bean
        @Singleton
        CascadeDefinition backInStock() {
            return new CascadeDefinition(INTENT, Priority.HIGH_INTENT, List.of(
                    CascadeDefinition.Step.push("push_back_in_stock_v1", Duration.ofHours(1))
                            .urgent().staleDevicesAllowed().orTemplate(NO_SIZE_TEMPLATE)
                            .waitAfter(Duration.ofMinutes(15)),
                    CascadeDefinition.Step.whatsapp("back_in_stock_v1")
                            .require(Guard.WA_MARKETING, Guard.WA_CAPABLE_OR_UNKNOWN)));
        }
    }

    @Override
    public boolean accepts(String eventName) {
        return eventName.equals("variant_restocked") || eventName.equals("variant_sold_out");
    }

    @Override
    public List<MessageIntent> handle(Connection c, Event e) throws SQLException {
        var variantId = e.props().get("variant_id");
        if (variantId != null && e.name().equals("variant_sold_out")) {
            soldOut(c, variantId);
            return List.of();
        }
        int quantity = parseInt(e.props().get("quantity"));
        if (variantId == null || quantity <= 0) return List.of();
        var handle = e.props().get("product_handle");
        var productTitle = e.props().get("product_title");
        var variantTitle = e.props().get("variant_title");
        int limit = Math.min(quantity, 10_000) * PER_UNIT;

        var intents = new ArrayList<MessageIntent>();
        try (var ps = Sql.prepare(c, """
                WITH due AS MATERIALIZED (
                       SELECT identity_id, variant_id FROM stock_waitlist
                        WHERE variant_id = ? AND notified_at IS NULL
                        ORDER BY created_at LIMIT ?
                          FOR UPDATE SKIP LOCKED)
                UPDATE stock_waitlist w SET notified_at = now()
                  FROM due
                 WHERE w.identity_id = due.identity_id AND w.variant_id = due.variant_id
                RETURNING w.identity_id, w.size_label, w.product_handle""", variantId, limit);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var identityId = rs.getObject("identity_id", UUID.class);
                var rowHandle = handle != null ? handle : rs.getString("product_handle");
                var vars = new HashMap<String, String>();
                vars.put("product", productTitle != null ? productTitle : fromHandle(rowHandle));
                vars.put("url", storefront.product(rowHandle, variantId));
                vars.put("product_path", "products/" + URLEncoder.encode(rowHandle, StandardCharsets.UTF_8)
                        + "?variant=" + URLEncoder.encode(variantId, StandardCharsets.UTF_8));
                var size = size(rs.getString("size_label"), variantTitle);
                if (size != null) vars.put("size", size);
                else vars.put(CascadeDefinition.TEMPLATE_VAR, NO_SIZE_TEMPLATE);
                intents.add(new MessageIntent(identityId, INTENT, variantId + ":" + identityId, vars, null));
            }
        }
        return intents;
    }

    /** Ends the pending alerts for a variant that sold out again before they went. */
    private void soldOut(Connection c, String variantId) throws SQLException {
        var subjects = new ArrayList<String>();
        try (var ps = Sql.prepare(c, """
                SELECT subject_key FROM cascade_runs
                 WHERE intent_key = ? AND split_part(subject_key, ':', 1) = ? AND status IN ('active', 'waiting')""",
                INTENT, variantId);
             var rs = ps.executeQuery()) {
            while (rs.next()) subjects.add(rs.getString(1));
        }
        for (var s : subjects) orchestrator.cancel(c, INTENT, s, "variant_sold_out");
    }

    /** What the shopper saw on the notify-me button, else Shopify's variant title; none for a one-size item. */
    static String size(String sizeLabel, String variantTitle) {
        if (isSize(sizeLabel)) return sizeLabel.strip();
        return isSize(variantTitle) ? variantTitle.strip() : null;
    }

    /** Shopify names the only variant of a one-size product "Default Title": that is not a size. */
    private static boolean isSize(String s) {
        return s != null && !s.isBlank() && !s.strip().equalsIgnoreCase("Default Title");
    }

    /** "floral-anarkali-kurta" → "Floral Anarkali Kurta", when Shopify gave no title. */
    static String fromHandle(String handle) {
        var words = new ArrayList<String>();
        for (var w : handle.split("-")) {
            if (!w.isEmpty()) words.add(w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1));
        }
        return String.join(" ", words);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
