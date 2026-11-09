package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.ingest.metrics.IngestMetrics.Result;
import in.brand.engage.persistence.Db;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /webhooks/magic/{token}}: Razorpay Magic Checkout's abandoned-cart
 * webhook (V62). In Razorpay: Magic Checkout → Setup &amp; Settings → Checkout
 * Setup → "Enable the abandoned webhook", URL
 * {PUBLIC_BASE_URL}/webhooks/magic/{RAZORPAY_MAGIC_WEBHOOK_TOKEN}.
 *
 * <p>Magic does not sign this webhook, so the secret is in the path, compared
 * in constant time, as for MSG91 delivery reports. Blank
 * secret = 404 for everything. A wrong secret also answers 404, so the
 * endpoint does not confirm it exists.
 *
 * <p>Verify, store, acknowledge; {@code MagicCheckoutInboxHandler} does the
 * work. No event id is sent, so a hash of the body dedupes retries.
 */
@Controller("/webhooks/magic")
public class MagicCheckoutWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(MagicCheckoutWebhookController.class);
    static final String TOPIC = "abandoned_checkout";

    private final InboxRepository inbox;
    private final IngestMetrics metrics;
    private final String secret;

    public MagicCheckoutWebhookController(InboxRepository inbox, IngestMetrics metrics, EngageProperties.Magic magic) {
        this.inbox = inbox;
        this.metrics = metrics;
        this.secret = magic.webhookToken() == null ? "" : magic.webhookToken().strip();
    }

    static boolean authorised(String configured, String presented) {
        if (configured == null || configured.length() < 16 || presented == null) return false;
        return Hmacs.constantTimeEquals(configured, presented);
    }

    @Post(uri = "/{token}", consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(@PathVariable("token") String token, @Body byte[] body) {
        if (!authorised(secret, token)) {
            LOG.warn("rejected Magic Checkout webhook: wrong or unset RAZORPAY_MAGIC_WEBHOOK_TOKEN");
            metrics.received("razorpay_magic", null, Result.UNAUTHORIZED);
            return HttpResponse.notFound();
        }
        if (body == null || body.length == 0) {
            metrics.received("razorpay_magic", TOPIC, Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        try {
            var stored = inbox.store("razorpay_magic", "sha256:" + Hmacs.sha256Hex("dedupe", body), TOPIC, body);
            LOG.info("magic abandoned-checkout webhook -> {}", stored);
            metrics.received("razorpay_magic", TOPIC, ShopifyWebhookController.result(stored));
            return HttpResponse.ok();
        } catch (Db.DbException e) {
            if (e.sqlState() != null && e.sqlState().startsWith("22")) {   // not JSON
                metrics.received("razorpay_magic", TOPIC, Result.BAD_REQUEST);
                return HttpResponse.badRequest();
            }
            throw e;
        }
    }
}
