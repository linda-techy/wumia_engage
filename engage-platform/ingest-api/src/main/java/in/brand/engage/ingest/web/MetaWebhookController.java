package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.core.meta.MetaSignatureVerifier;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.ingest.metrics.IngestMetrics.Result;
import in.brand.engage.persistence.Db;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WhatsApp Cloud API webhooks (P4-T01). In the Meta app: WhatsApp →
 * Configuration → Webhook, callback {PUBLIC_BASE_URL}/meta/webhook, verify
 * token = WA_WEBHOOK_VERIFY_TOKEN; subscribe to messages,
 * message_template_status_update, message_template_quality_update,
 * phone_number_quality_update.
 *
 * <p>Verify, store, acknowledge; nothing else inside Meta's timeout. One
 * POST can carry several entries, statuses and messages, so the delivery id
 * is the SHA-256 of the raw body: a retry of the same POST is one inbox row.
 * The handler dedupes each status and message by its own id.
 */
@Controller("/meta/webhook")
public class MetaWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(MetaWebhookController.class);

    private final MetaSignatureVerifier verifier;
    private final InboxRepository inbox;
    private final String verifyToken;
    private final IngestMetrics metrics;

    public MetaWebhookController(MetaSignatureVerifier verifier, InboxRepository inbox, EngageProperties.Meta meta,
                                 IngestMetrics metrics) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.verifyToken = meta.verifyToken() == null ? "" : meta.verifyToken();
        this.metrics = metrics;
    }

    /** Meta's subscription handshake: echo the challenge when the token matches. */
    @Get(produces = MediaType.TEXT_PLAIN)
    public HttpResponse<String> verify(@Nullable @QueryValue("hub.mode") String mode,
                                       @Nullable @QueryValue("hub.verify_token") String token,
                                       @Nullable @QueryValue("hub.challenge") String challenge) {
        if (!verifyToken.isBlank() && "subscribe".equals(mode) && token != null
                && Hmacs.constantTimeEquals(verifyToken, token) && challenge != null) {
            return HttpResponse.ok(challenge);
        }
        LOG.warn("refused Meta webhook verification (mode={}, token {})", mode,
                verifyToken.isBlank() ? "not configured: set WA_WEBHOOK_VERIFY_TOKEN" : "did not match");
        return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN);
    }

    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(HttpRequest<?> request, @Body byte[] body) {
        if (!verifier.verify(body, request.getHeaders().get("X-Hub-Signature-256"))) {
            LOG.warn("rejected Meta webhook: {}", verifier.configured()
                    ? "bad signature. Check WA_APP_SECRET is the app secret, not the access token."
                    : "WA_APP_SECRET is not set.");
            metrics.received("meta", null, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        var deliveryId = "sha256:" + Hmacs.sha256Hex("dedupe", body);
        try {
            var stored = inbox.storeMeta(deliveryId, body);
            LOG.info("meta webhook {} -> {}", deliveryId, stored);
            metrics.received("meta", "all", ShopifyWebhookController.result(stored));
            return HttpResponse.ok();
        } catch (Db.DbException e) {
            if (e.sqlState() != null && e.sqlState().startsWith("22")) {   // not JSON
                metrics.received("meta", "all", Result.BAD_REQUEST);
                return HttpResponse.badRequest();
            }
            throw e;
        }
    }
}
