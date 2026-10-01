package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.courier.CourierAdapter;
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
 * {@code POST /webhooks/courier}: the shipping aggregator's tracking webhook
 * (P1-T02, ADR-005). Verify with the adapter, store in the inbox, acknowledge;
 * {@code CourierInboxHandler} does the work. The path avoids the words
 * Shiprocket refuses in webhook URLs.
 *
 * <p>The aggregator sends no event id, so a hash of the body dedupes its
 * retries; distinct scans differ in their bodies.
 */
@Controller("/webhooks/courier")
public class CourierWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(CourierWebhookController.class);

    private final CourierAdapter adapter;
    private final InboxRepository inbox;
    private final IngestMetrics metrics;

    public CourierWebhookController(CourierAdapter adapter, InboxRepository inbox, IngestMetrics metrics) {
        this.adapter = adapter;
        this.inbox = inbox;
        this.metrics = metrics;
    }

    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(HttpRequest<?> request, @Body byte[] body) {
        if (!adapter.verify(request.getHeaders(), body)) {
            LOG.warn("rejected {} webhook: bad or missing token (COURIER_WEBHOOK_TOKEN)", adapter.name());
            metrics.received("courier", null, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        if (body == null || body.length == 0) {
            metrics.received("courier", adapter.name(), Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        try {
            var stored = inbox.store("courier", "sha256:" + Hmacs.sha256Hex("dedupe", body), adapter.name(), body);
            LOG.info("{} tracking webhook -> {}", adapter.name(), stored);
            metrics.received("courier", adapter.name(), ShopifyWebhookController.result(stored));
            return HttpResponse.ok();
        } catch (Db.DbException e) {
            if (e.sqlState() != null && e.sqlState().startsWith("22")) {
                metrics.received("courier", adapter.name(), Result.BAD_REQUEST);
                return HttpResponse.badRequest();
            }
            throw e;
        }
    }
}
