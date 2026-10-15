package in.brand.engage.channels.email;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marketing email through Amazon SES v2 ({@code POST /v2/email/outbound-emails},
 * P7-T01), signed with AWS Signature Version 4.
 *
 * <ul>
 * <li>Registered only when {@code EMAIL_FROM} is set: without it the email
 *     steps of journeys are skipped (the channel is off).</li>
 * <li>Marketing only: Shopify sends transactional email. Anything else is a
 *     permanent failure, never sent.</li>
 * <li>Every email carries {@code List-Unsubscribe} (the one-click link, plus
 *     the mailto address when configured) and {@code List-Unsubscribe-Post:
 *     List-Unsubscribe=One-Click} (RFC 8058, required by Gmail and Yahoo for
 *     bulk senders), and goes through configuration set {@code engage}, whose
 *     SNS destination reports bounces and complaints back.</li>
 * <li>Throttling (429) and 5xx are retried here, at most {@value #ATTEMPTS}
 *     attempts. A refusal never suppresses: bounces arrive by SNS.</li>
 * </ul>
 */
@Singleton
@Requires(property = "engage.email.from", pattern = ".+")
public class SesAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SesAdapter.class);
    static final int ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final URI endpoint;
    private final String region;
    private final String from;
    private final String configurationSet;
    private final String unsubscribeMailto;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final long backoffMs;
    private final Clock clock;

    public SesAdapter(JsonMapper json,
                      @Value("${engage.email.region:ap-south-1}") String region,
                      @Value("${engage.email.endpoint:}") String endpoint,
                      @Value("${engage.email.from}") String from,
                      @Value("${engage.email.configuration-set:engage}") String configurationSet,
                      @Value("${engage.email.unsubscribe-mailto:}") String unsubscribeMailto,
                      @Value("${engage.email.access-key-id:}") String accessKey,
                      @Value("${engage.email.secret-access-key:}") String secretKey,
                      @Value("${engage.email.session-token:}") String sessionToken,
                      @Value("${engage.email.retry-backoff-ms:500}") long backoffMs,
                      Clock clock) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("EMAIL_FROM is set but AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY are not: email cannot send.");
        }
        this.json = json;
        this.region = region.strip();
        var base = endpoint == null || endpoint.isBlank() ? "https://email." + this.region + ".amazonaws.com" : endpoint.strip();
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/v2/email/outbound-emails");
        this.from = from.strip();
        this.configurationSet = configurationSet;
        this.unsubscribeMailto = unsubscribeMailto == null ? "" : unsubscribeMailto.strip();
        this.accessKey = accessKey.strip();
        this.secretKey = secretKey.strip();
        this.sessionToken = sessionToken;
        this.backoffMs = backoffMs;
        this.clock = clock;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException {
        var to = addresses.email();
        if (to == null || !to.contains("@")) throw new ChannelException.Permanent("no_email", "no email address", false, List.of());
        var email = message.email();
        if (email == null) throw new ChannelException.Permanent("email_not_rendered", "no email content", false, List.of());
        if (!email.marketing()) {
            throw new ChannelException.Permanent("email_not_marketing", "Engage sends marketing email only; Shopify sends the rest",
                    false, List.of());
        }
        byte[] body;
        try {
            body = json.writeValueAsBytes(payload(to, message));
        } catch (IOException e) {
            throw new ChannelException.Permanent("payload", e.getMessage(), false, List.of());
        }
        ChannelException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return once(body);
            } catch (ChannelException.Transient e) {
                last = e;
                LOG.warn("send {}: SES attempt {}/{} failed: {}", message.sendId(), attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) pause(backoffMs << (attempt - 1));
            }
        }
        throw last;
    }

    Map<String, Object> payload(String to, RenderedMessage m) {
        var email = m.email();
        var unsubscribe = "<" + email.unsubscribeUrl() + ">"
                + (unsubscribeMailto.isEmpty() ? "" : ", <mailto:" + unsubscribeMailto + "?subject=unsubscribe>");
        var headers = new ArrayList<Map<String, String>>();
        headers.add(Map.of("Name", "List-Unsubscribe", "Value", unsubscribe));
        headers.add(Map.of("Name", "List-Unsubscribe-Post", "Value", "List-Unsubscribe=One-Click"));
        var simple = new LinkedHashMap<String, Object>();
        simple.put("Subject", Map.of("Data", email.subject(), "Charset", "UTF-8"));
        simple.put("Body", Map.of("Html", Map.of("Data", email.html(), "Charset", "UTF-8"),
                "Text", Map.of("Data", email.text(), "Charset", "UTF-8")));
        simple.put("Headers", headers);
        var out = new LinkedHashMap<String, Object>();
        out.put("FromEmailAddress", from);
        out.put("Destination", Map.of("ToAddresses", List.of(to)));
        out.put("Content", Map.of("Simple", simple));
        out.put("ConfigurationSetName", configurationSet);
        out.put("EmailTags", List.of(Map.of("Name", "send_id", "Value", Long.toString(m.sendId())),
                Map.of("Name", "template", "Value", m.templateKey().replaceAll("[^A-Za-z0-9_-]", "_"))));
        return out;
    }

    private DispatchResult once(byte[] body) throws ChannelException {
        var signed = SigV4.sign("POST", endpoint, Map.of("content-type", "application/json"), body, clock.instant(),
                region, "ses", accessKey, secretKey, sessionToken);
        var req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Amz-Date", signed.amzDate())
                .header("Authorization", signed.authorization());
        if (sessionToken != null && !sessionToken.isBlank()) req.header("X-Amz-Security-Token", sessionToken);
        HttpResponse<byte[]> res;
        try {
            res = http.send(req.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ChannelException.Transient("ses_timeout", e.getMessage(), List.of(), e);
        } catch (IOException e) {
            throw new ChannelException.Transient("ses_io", e.getMessage(), List.of(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException.Transient("ses_interrupted", null, List.of(), e);
        }
        var parsed = parse(res.body());
        int status = res.statusCode();
        if (status / 100 == 2) {
            if (parsed.get("MessageId") instanceof String id && !id.isBlank()) return new DispatchResult(id, 1, List.of());
            throw new ChannelException.Permanent("ses_no_message_id", "accepted without a message id", false, List.of());
        }
        // "MessageRejected:http://internal.amazon.com/..." → "MessageRejected"
        var type = res.headers().firstValue("x-amzn-ErrorType").orElse(String.valueOf(parsed.getOrDefault("__type", "")))
                .split(":", 2)[0].replaceAll(".*#", "");
        var text = parsed.get("message") instanceof String s ? s : parsed.get("Message") instanceof String s2 ? s2 : "HTTP " + status;
        if (status == 429 || status / 100 == 5 || type.contains("TooManyRequests") || type.contains("LimitExceeded")) {
            throw new ChannelException.Transient("ses_" + status, text, List.of(), null);
        }
        if (type.contains("AccountSuspended") || type.contains("SendingPaused") || type.contains("MailFromDomainNotVerified")
                || status == 403) {
            LOG.error("SES refused to send ({}: {}): check the SES account, the sending domain and the IAM key", type, text);
        }
        var code = type.isBlank() ? "ses_" + status : "ses_" + type.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
        throw new ChannelException.Permanent(code.length() > 60 ? code.substring(0, 60) : code, text, false, List.of());
    }

    private Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0) return Map.of();
        try {
            return json.readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
