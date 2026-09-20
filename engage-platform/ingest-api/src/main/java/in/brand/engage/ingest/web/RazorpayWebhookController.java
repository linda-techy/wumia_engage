package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.core.razorpay.RazorpaySignatureVerifier;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.db.Db;
import in.brand.engage.ingest.inbox.InboxRepository;
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
 * Razorpay webhooks. In the Razorpay Dashboard (Test Mode first):
 * Account &amp; Settings → Webhooks → Add, URL {PUBLIC_BASE_URL}/webhooks/razorpay,
 * secret = RAZORPAY_WEBHOOK_SECRET, events payment.failed, payment.captured,
 * payment.authorized.
 *
 * <p>Razorpay delivers at-least-once and retries if there is no 2xx within
 * 5 seconds, so this only verifies, stores and acknowledges.
 */
@Controller("/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpaySignatureVerifier verifier;
    private final InboxRepository inbox;
    private final String mode;

    public RazorpayWebhookController(RazorpaySignatureVerifier verifier, InboxRepository inbox,
                                     EngageProperties.Razorpay razorpay) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.mode = razorpay.mode();
    }

    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(HttpRequest<?> request, @Body byte[] body) {
        var h = request.getHeaders();
        if (!verifier.verify(body, h.get("X-Razorpay-Signature"))) {
            LOG.warn("rejected Razorpay webhook: bad signature. Check RAZORPAY_WEBHOOK_SECRET is the "
                    + "webhook secret, not the API key secret.");
            return HttpResponse.unauthorized();
        }
        // x-razorpay-event-id is unique per event and is the dedupe key. If it
        // is ever absent, a hash of the body still dedupes identical retries.
        var eventId = h.get("x-razorpay-event-id");
        var deliveryId = eventId != null && !eventId.isBlank() ? eventId : "sha256:" + Hmacs.sha256Hex("dedupe", body);
        try {
            var stored = inbox.store("razorpay", deliveryId, null, body);   // topic read from payload "event"
            LOG.info("razorpay[{}] event {} -> {}", mode, deliveryId, stored);
            return HttpResponse.ok();
        } catch (Db.DbException e) {
            if (e.sqlState() != null && e.sqlState().startsWith("22")) return HttpResponse.badRequest();
            throw e;
        }
    }
}
