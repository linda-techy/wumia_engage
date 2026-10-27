package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.ingest.metrics.IngestMetrics.Result;
import in.brand.engage.ingest.sms.Msg91Dlr;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /webhooks/sms?token=…}: MSG91 delivery reports (P4-T06).
 * MSG91 signs nothing, so the URL carries a secret ({@code MSG91_DLR_TOKEN});
 * blank = every report is refused. The report is normalised
 * ({@link Msg91Dlr}), stored in the inbox as source {@code msg91} and
 * acknowledged; {@code Msg91InboxHandler} applies it.
 */
@Controller("/webhooks/sms")
public class Msg91DlrController {

    private static final Logger LOG = LoggerFactory.getLogger(Msg91DlrController.class);

    private final String token;
    private final InboxRepository inbox;
    private final IngestMetrics metrics;
    private final JsonMapper json;

    public Msg91DlrController(EngageProperties.Msg91 config, InboxRepository inbox, IngestMetrics metrics, JsonMapper json) {
        this.token = config.dlrToken() == null ? "" : config.dlrToken().strip();
        this.inbox = inbox;
        this.metrics = metrics;
        this.json = json;
    }

    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(HttpRequest<?> request, @QueryValue(defaultValue = "") String token, @Body byte[] body) {
        if (this.token.isEmpty() || !Hmacs.constantTimeEquals(this.token, token)) {
            LOG.warn("rejected MSG91 delivery report: bad or missing token (MSG91_DLR_TOKEN)");
            metrics.received("msg91", null, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        java.util.Map<String, Object> doc;
        try {
            doc = body == null ? null : Msg91Dlr.normalise(json, body,
                    request.getContentType().map(MediaType::toString).orElse(null));
        } catch (IOException | RuntimeException e) {
            doc = null;
        }
        if (doc == null) {
            LOG.warn("rejected MSG91 delivery report: unreadable ({} bytes, {})", body == null ? 0 : body.length,
                    request.getContentType().map(MediaType::toString).orElse("no content type"));
            metrics.received("msg91", "dlr", Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        byte[] normalised;
        try {
            normalised = json.writeValueAsBytes(doc);
        } catch (IOException e) {
            metrics.received("msg91", "dlr", Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        var stored = inbox.store("msg91", "sha256:" + Hmacs.sha256Hex("dedupe", body), "dlr", normalised);
        metrics.received("msg91", "dlr", ShopifyWebhookController.result(stored));
        return HttpResponse.ok();
    }
}
