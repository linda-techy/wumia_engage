package in.brand.engage.ingest.config;

import in.brand.engage.core.privacy.CustomerAllowlist;
import io.micronaut.context.annotation.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to start while required values in config/local.env are still
 * placeholders, and names each one. The Gradle run task performs the same
 * check before the JVM starts; this covers launches from an IDE.
 */
@Context
public class StartupConfigCheck {

    private static final Logger LOG = LoggerFactory.getLogger(StartupConfigCheck.class);

    public StartupConfigCheck(EngageProperties.Shopify shopify, EngageProperties.Razorpay razorpay,
                              CustomerAllowlist allowlist) {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("SHOPIFY_SHOP_DOMAIN", shopify.shopDomain());
        required.put("SHOPIFY_API_SECRET", shopify.apiSecret());
        required.put("RAZORPAY_WEBHOOK_SECRET", razorpay.webhookSecret());

        List<String> missing = new ArrayList<>();
        required.forEach((name, value) -> {
            if (value == null || value.isBlank() || value.contains("CHANGE_ME")
                    || value.equals("your-store.myshopify.com")) {
                missing.add(name);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Edit config/local.env and set: " + String.join(", ", missing));
        }
        LOG.info("Config OK — shop={} razorpay.mode={} customers={}",
                shopify.shopDomain(), razorpay.mode(), allowlist);
    }
}
