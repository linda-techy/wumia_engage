package in.brand.engage.channels.sms;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLT SMS through MSG91's Flow API (P4-T06): {@code POST {base}/api/v5/flow}
 * with the MSG91 template id and one recipient carrying the variables by name.
 *
 * <ul>
 * <li>Registered only when {@code MSG91_AUTH_KEY} is set: without it the
 *     router has no SMS adapter and records {@code no_adapter}.</li>
 * <li>Before any HTTP call the message is checked against its DLT
 *     registration ({@link in.brand.engage.channels.SmsCall#mismatch()}): a
 *     wrong slot count or an over-long value is a permanent failure, because
 *     the operator would drop it silently after MSG91 said "success".</li>
 * <li>The MSG91 template holds the DLT template id and the sender header; its
 *     variables must be named like ours ({@code ##order_no##}).</li>
 * <li>The returned {@code message} is the request id that delivery reports
 *     carry; it becomes {@code sends.provider_id}.</li>
 * <li>Timeouts and 5xx are retried here, at most {@value #ATTEMPTS} attempts.
 *     A refusal is never the recipient's fault, so nothing suppresses.</li>
 * </ul>
 */
@Singleton
@Requires(property = "engage.sms.msg91.auth-key", pattern = ".+")
public class Msg91Adapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(Msg91Adapter.class);
    static final int ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final URI endpoint;
    private final String authKey;
    private final long backoffMs;

    public Msg91Adapter(JsonMapper json,
                        @Value("${engage.sms.msg91.base:`https://control.msg91.com`}") String base,
                        @Value("${engage.sms.msg91.auth-key}") String authKey,
                        @Value("${engage.sms.msg91.retry-backoff-ms:500}") long backoffMs) {
        this.json = json;
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/api/v5/flow");
        this.authKey = authKey.strip();
        this.backoffMs = backoffMs;
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException {
        var to = addresses.phone();
        if (to == null || !to.matches("91[6-9]\\d{9}")) {
            throw new ChannelException.Permanent("no_phone", "no Indian mobile to send to", false, List.of());
        }
        var call = message.sms();
        if (call == null) throw new ChannelException.Permanent("sms_no_template", "no DLT template call", false, List.of());
        var mismatch = call.mismatch();
        if (mismatch != null) {
            LOG.error("send {}: {} does not match its DLT registration: {}", message.sendId(), message.templateKey(), mismatch);
            throw new ChannelException.Permanent("dlt_mismatch", mismatch, false, List.of());
        }

        var recipient = new LinkedHashMap<String, Object>();
        recipient.put("mobiles", to);
        for (int i = 0; i < call.names().size(); i++) recipient.put(call.names().get(i), call.values().get(i));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("template_id", call.providerTemplateId());
        payload.put("short_url", "0");      // our links are on the DLT-whitelisted store domain; MSG91's are not
        payload.put("recipients", List.of(recipient));
        byte[] body;
        try {
            body = json.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new ChannelException.Permanent("payload", e.getMessage(), false, List.of());
        }

        ChannelException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return once(body);
            } catch (ChannelException.Transient e) {
                last = e;
                LOG.warn("send {}: MSG91 attempt {}/{} failed: {}", message.sendId(), attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) pause(backoffMs << (attempt - 1));
            }
        }
        throw last;
    }

    private DispatchResult once(byte[] body) throws ChannelException {
        var req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("authkey", authKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ChannelException.Transient("msg91_timeout", e.getMessage(), List.of(), e);
        } catch (IOException e) {
            throw new ChannelException.Transient("msg91_io", e.getMessage(), List.of(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException.Transient("msg91_interrupted", null, List.of(), e);
        }
        var parsed = parse(res.body());
        var type = String.valueOf(parsed.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        var text = parsed.get("message") instanceof String s ? s : "HTTP " + res.statusCode();
        int status = res.statusCode();
        if (status / 100 == 2 && type.equals("success")) {
            if (text.isBlank() || text.startsWith("HTTP ")) {
                throw new ChannelException.Permanent("msg91_no_request_id", "accepted without a request id", false, List.of());
            }
            return new DispatchResult(text, 1, List.of());
        }
        if (status == 429 || status / 100 == 5) throw new ChannelException.Transient("msg91_" + status, text, List.of(), null);
        if (status == 401 || status == 403 || text.toLowerCase(Locale.ROOT).contains("auth")) {
            LOG.error("MSG91 refused the auth key ({}): check MSG91_AUTH_KEY and the API IP whitelist", text);
            throw new ChannelException.Permanent("msg91_auth", text, false, List.of());
        }
        throw new ChannelException.Permanent("msg91_rejected", text, false, List.of());
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
