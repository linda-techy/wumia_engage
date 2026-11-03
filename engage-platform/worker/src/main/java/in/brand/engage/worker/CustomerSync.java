package in.brand.engage.worker;

import in.brand.engage.core.privacy.CustomerAllowlist;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.worker.shopify.ShopifyAdmin;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code worker shopify-customers-sync [--yes]}: every Shopify customer into
 * Engage (owner, 2026-11-04), so a win-back can reach the buyers from before
 * Engage was installed. Dry run without {@code --yes}: counts only.
 *
 * <p>Read-only towards Shopify ({@code read_customers}). Each customer becomes
 * a {@code customers/update} row in the webhook inbox, in the shape Shopify's
 * webhook has, and ingest-api processes it like any other: the same identity
 * rules, and consent only as Shopify records it (the WhatsApp setting on the
 * phone number, the phone checkbox, email marketing). Nothing is sent from here.
 *
 * <p>The payload adds Shopify's lifetime history ({@code orders_count},
 * {@code last_order_at}, {@code total_spent}) for the segments. The app reads
 * orders of the last 60 days only, so an older last order comes back empty:
 * the date is then recorded as "at least 60 days ago" (the sync time less 60
 * days), which keeps the customer in a "last order older than 60 days" segment.
 *
 * <p>The delivery id holds the customer's {@code updatedAt}: a second run adds
 * only the customers that changed. The customer allowlist applies, as to webhooks.
 */
final class CustomerSync {

    static final int PAGE = 100;
    static final int MAX_PAGES = 1000;
    static final Duration ORDERS_WINDOW = Duration.ofDays(60);

    static final String QUERY = """
            query($after: String) {
              customers(first: %d, after: $after, sortKey: ID) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  legacyResourceId firstName tags updatedAt numberOfOrders
                  amountSpent { amount }
                  lastOrder { createdAt }
                  defaultAddress { city provinceCode }
                  defaultEmailAddress { emailAddress marketingState marketingUpdatedAt }
                  defaultPhoneNumber {
                    phoneNumber
                    smsMarketingConsent { state updatedAt }
                    whatsAppMarketingConsent { state updatedAt collectedFrom }
                  }
                }
              }
            }""".formatted(PAGE);

    /** What a pass found. */
    record Counts(int customers, int withPhone, int whatsAppSubscribed, int buyers, int stored, int filtered) {}

    private CustomerSync() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].equals("shopify-customers-sync");
    }

    static void run(String[] args) {
        boolean yes = args.length > 1 && args[1].equals("--yes");
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            var admin = ctx.getBean(ShopifyAdmin.class);
            var db = ctx.getBean(Db.class);
            var json = ctx.getBean(JsonMapper.class);
            var allowlist = CustomerAllowlist.parse(System.getenv("CUSTOMER_ALLOWLIST_EMAILS"));
            var counts = sync(admin, db, json, allowlist, yes, java.time.Instant.now());
            System.out.printf("shopify-customers-sync: %d customers, %d with a phone, %d WhatsApp marketing subscribed, "
                            + "%d have ordered%n", counts.customers(), counts.withPhone(), counts.whatsAppSubscribed(),
                    counts.buyers());
            System.out.println(yes
                    ? "  " + counts.stored() + " new or changed queued for ingest; " + counts.filtered() + " outside the customer allowlist"
                    : "  dry run: nothing written (add --yes)");
        } catch (ShopifyAdmin.Failure e) {
            System.out.println("Shopify call failed: " + e.getMessage());
        }
    }

    static Counts sync(ShopifyAdmin admin, Db db, JsonMapper json, CustomerAllowlist allowlist, boolean write,
                       java.time.Instant now) throws ShopifyAdmin.Failure {
        int customers = 0, phones = 0, wa = 0, buyers = 0, stored = 0, filtered = 0;
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var vars = new LinkedHashMap<String, Object>();
            vars.put("after", after);
            var root = admin.graphql(QUERY, vars, Duration.ofSeconds(30));
            if (root.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
                throw new ShopifyAdmin.Failure("Shopify reported: " + errors);
            }
            var connection = map(map(root.get("data")).get("customers"));
            var payloads = new ArrayList<Map<String, Object>>();
            if (connection.get("nodes") instanceof List<?> nodes) {
                for (var n : nodes) {
                    var p = payload(map(n), now);
                    if (p == null) continue;
                    customers++;
                    if (p.get("phone") != null) phones++;
                    if ("subscribed".equals(map(p.get("whatsapp_marketing_consent")).get("state"))) wa++;
                    if (p.get("orders_count") instanceof Long count && count > 0) buyers++;
                    if (!allowlist.allowAll() && !(p.get("email") instanceof String email && allowlist.allows(email))) {
                        filtered++;
                        continue;
                    }
                    payloads.add(p);
                }
            }
            if (write && !payloads.isEmpty()) {
                stored += db.inTx(c -> {
                    int k = 0;
                    for (var p : payloads) k += store(c, json, p);
                    return k;
                });
            }
            var info = map(connection.get("pageInfo"));
            if (!Boolean.TRUE.equals(info.get("hasNextPage"))) break;
            after = (String) info.get("endCursor");
        }
        return new Counts(customers, phones, wa, buyers, stored, filtered);
    }

    /** The REST webhook shape ingest-api's shopify_customer.sql reads. Null when the node has no id. */
    static Map<String, Object> payload(Map<String, Object> node, java.time.Instant now) {
        var id = str(node.get("legacyResourceId"));
        if (id == null) return null;
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id);
        p.put("first_name", str(node.get("firstName")));
        p.put("updated_at", str(node.get("updatedAt")));
        p.put("tags", node.get("tags") instanceof List<?> tags
                ? String.join(", ", tags.stream().map(String::valueOf).toList()) : "");

        var email = map(node.get("defaultEmailAddress"));
        p.put("email", str(email.get("emailAddress")));
        p.put("email_marketing_consent", consent(email.get("marketingState"), email.get("marketingUpdatedAt"), null));

        var phone = map(node.get("defaultPhoneNumber"));
        p.put("phone", str(phone.get("phoneNumber")));
        var sms = map(phone.get("smsMarketingConsent"));
        p.put("sms_marketing_consent", consent(sms.get("state"), sms.get("updatedAt"), null));
        var whatsapp = map(phone.get("whatsAppMarketingConsent"));
        p.put("whatsapp_marketing_consent", consent(whatsapp.get("state"), whatsapp.get("updatedAt"),
                whatsapp.get("collectedFrom")));

        long orders = node.get("numberOfOrders") == null ? 0 : Long.parseLong(String.valueOf(node.get("numberOfOrders")));
        p.put("orders_count", orders);
        var last = str(map(node.get("lastOrder")).get("createdAt"));
        if (last == null && orders > 0) last = now.minus(ORDERS_WINDOW).toString();   // older than the app can read
        p.put("last_order_at", last);
        p.put("total_spent", str(map(node.get("amountSpent")).get("amount")));

        var address = map(node.get("defaultAddress"));
        var a = new LinkedHashMap<String, Object>();
        a.put("city", str(address.get("city")));
        a.put("province_code", str(address.get("provinceCode")));
        p.put("default_address", a);
        p.put("_engage_source", "shopify-customers-sync");
        return p;
    }

    private static Map<String, Object> consent(Object state, Object updatedAt, Object collectedFrom) {
        var m = new LinkedHashMap<String, Object>();
        m.put("state", state == null ? null : String.valueOf(state).toLowerCase(Locale.ROOT));
        m.put("consent_updated_at", str(updatedAt));
        if (collectedFrom != null) m.put("consent_collected_from", String.valueOf(collectedFrom));
        return m;
    }

    /** @return 1 when the row is new (the customer changed since the last run) */
    static int store(Connection c, JsonMapper json, Map<String, Object> p) throws SQLException {
        String body;
        try {
            body = json.writeValueAsString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Sql.update(c, """
                INSERT INTO webhook_inbox (source, delivery_id, topic, payload)
                VALUES ('shopify', ?, 'customers/update', ?::jsonb)
                ON CONFLICT (source, delivery_id) DO NOTHING""",
                "sync:customer:" + p.get("id") + ":" + p.get("updated_at"), body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        var s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }
}
