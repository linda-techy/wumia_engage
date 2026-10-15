package in.brand.engage.ingest.web;

import in.brand.engage.core.aws.SnsSignatureVerifier;
import in.brand.engage.ingest.inbox.InboxRepository;
import in.brand.engage.ingest.metrics.IngestMetrics;
import in.brand.engage.ingest.metrics.IngestMetrics.Result;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /webhooks/ses}: Amazon SNS notifications for the SES
 * configuration set (P7-T01): bounces, complaints and deliveries.
 *
 * <ul>
 * <li>Every message is verified ({@link SnsSignatureVerifier}: signature
 *     version 2, certificate from an SNS host) and must come from
 *     {@code SES_SNS_TOPIC_ARN}; anything else is 401. Blank ARN = all 401.</li>
 * <li>{@code SubscriptionConfirmation} for that topic is confirmed here (a GET
 *     of its SNS SubscribeURL), so setting up the topic needs no manual step.</li>
 * <li>A {@code Notification}'s SES event is stored in the inbox as source
 *     {@code ses}, keyed by the SNS message id; {@code SesInboxHandler} applies it.</li>
 * </ul>
 */
@Controller("/webhooks/ses")
public class SesSnsController {

    private static final Logger LOG = LoggerFactory.getLogger(SesSnsController.class);

    private final String topicArn;
    private final InboxRepository inbox;
    private final IngestMetrics metrics;
    private final JsonMapper json;
    private final SnsSignatureVerifier verifier;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SesSnsController(@Value("${engage.ses.topic-arn:}") String topicArn, InboxRepository inbox, IngestMetrics metrics,
                            JsonMapper json, SnsCertificates certificates) {
        this.topicArn = topicArn == null ? "" : topicArn.strip();
        this.inbox = inbox;
        this.metrics = metrics;
        this.json = json;
        this.verifier = new SnsSignatureVerifier(certificates::get);
    }

    @Post(consumes = MediaType.ALL)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(@Body byte[] body) {
        Map<String, String> m;
        try {
            Map<String, Object> raw = json.readValue(body, Argument.mapOf(String.class, Object.class));
            m = new HashMap<>();
            raw.forEach((k, v) -> { if (v != null) m.put(k, String.valueOf(v)); });
        } catch (Exception e) {
            metrics.received("ses", null, Result.BAD_REQUEST);
            return HttpResponse.badRequest();
        }
        if (topicArn.isEmpty() || !topicArn.equals(m.get("TopicArn")) || !verifier.verify(m)) {
            LOG.warn("rejected SNS message: unknown topic or bad signature (SES_SNS_TOPIC_ARN)");
            metrics.received("ses", null, Result.UNAUTHORIZED);
            return HttpResponse.unauthorized();
        }
        switch (String.valueOf(m.get("Type"))) {
            case "SubscriptionConfirmation" -> {
                confirm(m.get("SubscribeURL"));
                return HttpResponse.ok();
            }
            case "Notification" -> {
                String type;
                try {
                    Map<String, Object> event = json.readValue(m.get("Message").getBytes(StandardCharsets.UTF_8),
                            Argument.mapOf(String.class, Object.class));
                    type = String.valueOf(event.getOrDefault("notificationType", event.getOrDefault("eventType", "unknown")));
                } catch (Exception e) {
                    metrics.received("ses", null, Result.BAD_REQUEST);
                    return HttpResponse.badRequest();
                }
                var stored = inbox.store("ses", "sns:" + m.get("MessageId"), type, m.get("Message").getBytes(StandardCharsets.UTF_8));
                metrics.received("ses", type, ShopifyWebhookController.result(stored));
                return HttpResponse.ok();
            }
            default -> {
                return HttpResponse.ok();                     // UnsubscribeConfirmation: nothing to do
            }
        }
    }

    private void confirm(String subscribeUrl) {
        try {
            var u = URI.create(subscribeUrl);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().matches("sns\\.[a-z0-9-]+\\.amazonaws\\.com")) {
                LOG.warn("SNS subscription with an unexpected SubscribeURL host; not confirmed");
                return;
            }
            var res = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).GET().build(), BodyHandlers.discarding());
            LOG.info("SNS subscription to {} confirmed: HTTP {}", topicArn, res.statusCode());
        } catch (Exception e) {
            LOG.error("SNS subscription confirmation failed: {}", e.getMessage());
        }
    }
}
