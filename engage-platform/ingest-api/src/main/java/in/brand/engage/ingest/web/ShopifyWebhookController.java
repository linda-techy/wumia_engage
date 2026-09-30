package in.brand.engage.ingest.web;

import in.brand.engage.core.shopify.ShopifyWebhookVerifier;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.ingest.metrics.IngestMetrics.Result;
import in.brand.engage.persistence.Db;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shopify webhooks. Register the address {PUBLIC_BASE_URL}/webhooks/shopify
 * for every topic in docs/technical/phase-1-core-platform.md §4.
 *
 * <p>Inside Shopify's 5-second budget this does only: verify the HMAC over the
 * raw bytes, store in the inbox, return 200. Processing happens later.
 */
@Controller("/webhooks/shopify")
public class ShopifyWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(ShopifyWebhookController.class);

    private final ShopifyWebhookVerifier verifier;
    private final InboxRepository inbox;
    private final String shopDomain;
    private final IngestMetrics metrics;

    public ShopifyWebhookController(ShopifyWebhookVerifier verifier, InboxRepository inbox,
                                    EngageProperties.Shopify shopify, IngestMetrics metrics) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.metrics = metrics;
        this.shopDomain = shopify.shopDomain();
    }

    // Bind the body as byte[]: the HMAC is computed over these exact bytes.
    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(HttpRequest<?> request, @Body byte[] body) {
        var h = request.getHeaders();
        var shop = h.get("X-Shopify-Shop-Domain");
        var topic = h.get("X-Shopify-Topic");
        var deliveryId = h.get("X-Shopify-Webhook-Id");

        if (shop == null || !shop.equalsIgnoreCase(shopDomain)) {
            LOG.warn("rejected Shopify webhook: shop={} topic={} (expected shop {})", shop, topic, shopDomain);
            metrics.received("shopify", topic, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        if (!verifier.verify(body, h.get("X-Shopify-Hmac-Sha256"))) {
            LOG.warn("rejected Shopify webhook: shop={} topic={} (bad or missing HMAC)", shop, topic);
            metrics.received("shopify", topic, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        if (topic == null || deliveryId == null || body == null || body.length == 0) {
            metrics.received("shopify", topic, Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        try {
            var stored = inbox.store("shopify", deliveryId, topic, body);
            if (stored == InboxRepository.Stored.FILTERED) {
                // 200 so Shopify does not retry; the payload was not written anywhere.
                LOG.info("shopify {} {} dropped: not an allowlisted customer", topic, deliveryId);
            } else {
                LOG.info("shopify {} {} -> {}", topic, deliveryId, stored);
            }
            metrics.received("shopify", topic, result(stored));
            return HttpResponse.ok();
        } catch (Db.DbException e) {
            // 22xxx = malformed JSON. Retrying will not fix it, so do not ask Shopify to.
            if (e.sqlState() != null && e.sqlState().startsWith("22")) {
                metrics.received("shopify", topic, Result.BAD_REQUEST);
                return HttpResponse.badRequest();
            }
            throw e;
        }
    }

    static Result result(InboxRepository.Stored stored) {
        return switch (stored) {
            case NEW -> Result.STORED;
            case DUPLICATE -> Result.DUPLICATE;
            case FILTERED -> Result.FILTERED;
        };
    }
}
