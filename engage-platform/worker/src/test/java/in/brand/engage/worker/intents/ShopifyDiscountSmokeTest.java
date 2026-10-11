package in.brand.engage.worker.intents;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.serde.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Creates one real code on the dev store, to prove Shopify accepts what
 * {@link ShopifyDiscountCodes} sends. Off unless DISCOUNT_SMOKE=1, and only
 * against wumika-dev:
 * {@code DISCOUNT_SMOKE=1 gradlew :worker:test --tests '*ShopifyDiscountSmokeTest' -PenvFile=config/devstore.env}.
 * The code is single use, 10% off and expires after an hour.
 */
@EnabledIfEnvironmentVariable(named = "DISCOUNT_SMOKE", matches = "1")
class ShopifyDiscountSmokeTest {

    @Test void the_dev_store_accepts_a_single_use_code() throws Exception {
        var shop = System.getenv("SHOPIFY_SHOP_DOMAIN");
        assertNotNull(shop);
        assertTrue(shop.startsWith("wumika-dev."), "refusing to create a code on " + shop + ": dev store only");
        var codes = new ShopifyDiscountCodes(ObjectMapper.getDefault(), shop, System.getenv("SHOPIFY_CLIENT_ID"),
                System.getenv("SHOPIFY_API_SECRET"), System.getenv("SHOPIFY_ADMIN_TOKEN"), "2026-07", Clock.systemUTC());
        var code = "WUMTEST" + JourneyDiscount.newCode().substring(3, 7);
        var now = Instant.now();
        codes.create(new DiscountCodes.Spec(code, "Engage smoke test " + code + " (delete me)", 10, 0, now,
                now.plus(Duration.ofHours(1))));
        System.out.println("SMOKE: created discount code " + code + " on " + shop);
    }
}
