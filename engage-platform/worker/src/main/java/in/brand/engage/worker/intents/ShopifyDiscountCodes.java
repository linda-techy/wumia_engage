package in.brand.engage.worker.intents;

import in.brand.engage.worker.shopify.ShopifyAdmin;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code discountCodeBasicCreate} on the Shopify Admin GraphQL API. Needs the
 * {@code write_discounts} scope on the store's app; without it Shopify answers
 * ACCESS_DENIED and the discount step is skipped (the reminders still go).
 *
 * <p>Token and transport: {@link ShopifyAdmin}.
 */
@Singleton
public class ShopifyDiscountCodes implements DiscountCodes {

    static final String MUTATION = """
            mutation($d: DiscountCodeBasicInput!) {
              discountCodeBasicCreate(basicCodeDiscount: $d) {
                codeDiscountNode { id }
                userErrors { field message code }
              }
            }""";
    private final ShopifyAdmin admin;

    @Inject
    public ShopifyDiscountCodes(ShopifyAdmin admin) {
        this.admin = admin;
    }

    /** For the smoke test against a real store. */
    public ShopifyDiscountCodes(JsonMapper json, String shopDomain, String clientId, String clientSecret, String fixedToken,
                                String apiVersion, Clock clock) {
        this(new ShopifyAdmin(json, shopDomain, clientId, clientSecret, fixedToken, apiVersion, clock));
    }

    @Override
    public void create(Spec spec) throws Failed {
        Map<String, Object> root;
        try {
            root = admin.graphql(MUTATION, Map.of("d", input(spec)), Duration.ofSeconds(10));
        } catch (ShopifyAdmin.Failure e) {
            throw new Failed(e.getMessage().startsWith("access denied") ? "access denied: the app needs write_discounts" : e.getMessage(), false);
        }
        if (root.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            var text = String.valueOf(errors);
            throw new Failed(text.contains("ACCESS_DENIED") ? "access denied: the app needs write_discounts" : text, false);
        }
        if (root.get("data") instanceof Map<?, ?> data && data.get("discountCodeBasicCreate") instanceof Map<?, ?> result) {
            if (result.get("userErrors") instanceof List<?> ue && !ue.isEmpty()) {
                boolean taken = ue.stream().anyMatch(e -> e instanceof Map<?, ?> m && "TAKEN".equals(m.get("code")));
                throw new Failed("Shopify refused the code: " + ue, taken);
            }
            if (result.get("codeDiscountNode") instanceof Map<?, ?>) return;
        }
        throw new Failed("unexpected Shopify response: " + root, false);
    }

    /** The mutation's input, exposed for tests. */
    static Map<String, Object> input(Spec spec) {
        var d = new LinkedHashMap<String, Object>();
        d.put("title", spec.title());
        d.put("code", spec.code());
        d.put("startsAt", spec.startsAt().toString());
        d.put("endsAt", spec.endsAt().toString());
        d.put("context", Map.of("all", "ALL"));
        d.put("customerGets", Map.of(
                "value", Map.of("percentage", BigDecimal.valueOf(spec.percent()).movePointLeft(2)),
                "items", Map.of("all", true)));
        d.put("usageLimit", 1);                 // single use: a leaked code is worth one order
        d.put("appliesOncePerCustomer", true);
        if (spec.minOrderPaise() > 0) {
            d.put("minimumRequirement", Map.of("subtotal", Map.of("greaterThanOrEqualToSubtotal",
                    BigDecimal.valueOf(spec.minOrderPaise()).movePointLeft(2).toPlainString())));
        }
        d.put("combinesWith", Map.of("orderDiscounts", false, "productDiscounts", false, "shippingDiscounts", true));
        return d;
    }
}
