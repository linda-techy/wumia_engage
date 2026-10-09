package in.brand.engage.channels.whatsapp;

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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WhatsApp Cloud API (P4-T02): {@code POST {graph}/{version}/{phone-number-id}/messages}.
 *
 * <ul>
 * <li>Registered only when {@code WA_PHONE_NUMBER_ID} is set: without it the
 *     router has no WhatsApp adapter and records {@code no_adapter}.</li>
 * <li>{@code to} is {@code 91XXXXXXXXXX} without a plus ({@code Msisdn}).</li>
 * <li>Transient failures (130429, 131056, 5xx, timeouts) are retried here, at
 *     most {@value #ATTEMPTS} attempts with a doubling pause; never the
 *     permanent codes ({@link WaError}).</li>
 * <li>The Graph API version is pinned by {@code WA_GRAPH_VERSION}.</li>
 * </ul>
 */
@Singleton
@Requires(property = "engage.whatsapp.phone-number-id", pattern = ".+")
public class WhatsAppCloudAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppCloudAdapter.class);
    static final int ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final URI endpoint;
    private final String token;
    private final long backoffMs;

    public WhatsAppCloudAdapter(JsonMapper json,
                                @Value("${engage.whatsapp.graph-base:`https://graph.facebook.com`}") String graphBase,
                                @Value("${engage.whatsapp.graph-version:v23.0}") String version,
                                @Value("${engage.whatsapp.phone-number-id}") String phoneNumberId,
                                @Value("${engage.whatsapp.access-token:}") String token,
                                @Value("${engage.whatsapp.retry-backoff-ms:500}") long backoffMs) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("WA_PHONE_NUMBER_ID is set but WA_ACCESS_TOKEN is not: WhatsApp cannot send.");
        }
        this.json = json;
        var v = version == null || version.isBlank() ? "v23.0" : version.strip();   // an empty WA_GRAPH_VERSION= in .env
        this.endpoint = URI.create(graphBase.replaceAll("/+$", "") + "/" + v + "/" + phoneNumberId.strip() + "/messages");
        this.token = token;
        this.backoffMs = backoffMs;
    }

    @Override
    public Channel channel() {
        return Channel.WHATSAPP;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException {
        var to = addresses.phone();
        if (to == null || !to.matches("91[6-9]\\d{9}")) {
            throw new ChannelException.Permanent("no_phone", "no Indian mobile to send to", false, List.of());
        }
        byte[] body;
        try {
            body = json.writeValueAsBytes(WaPayloads.message(to, message));
        } catch (IOException e) {
            throw new ChannelException.Permanent("payload", e.getMessage(), false, List.of());
        }
        ChannelException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return once(body);
            } catch (ChannelException.Transient e) {
                last = e;
                LOG.warn("send {}: WhatsApp attempt {}/{} failed: {}", message.sendId(), attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) pause(backoffMs << (attempt - 1));
            }
        }
        throw last;
    }

    private DispatchResult once(byte[] body) throws ChannelException {
        var req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ChannelException.Transient("wa_timeout", e.getMessage(), List.of(), e);
        } catch (IOException e) {
            throw new ChannelException.Transient("wa_io", e.getMessage(), List.of(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException.Transient("wa_interrupted", null, List.of(), e);
        }
        Map<String, Object> parsed = parse(res.body());
        if (res.statusCode() / 100 == 2) {
            if (parsed.get("messages") instanceof List<?> list && !list.isEmpty()
                    && list.getFirst() instanceof Map<?, ?> m && m.get("id") instanceof String wamid) {
                return new DispatchResult(wamid, 1, List.of());
            }
            throw new ChannelException.Permanent("wa_no_message_id", "accepted without a message id", false, List.of());
        }
        Integer code = null;
        String text = "HTTP " + res.statusCode();
        if (parsed.get("error") instanceof Map<?, ?> err) {
            if (err.get("code") instanceof Number n) code = n.intValue();
            if (err.get("message") instanceof String s) text = s;
        }
        throw WaError.classify(res.statusCode(), code, text);
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
