package in.brand.engage.admin.inbox;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few WhatsApp Cloud API calls the inbox makes for staff: a free-form
 * reply inside the 24 h window, a template outside it, and fetching a
 * customer's photo or document. Automated messages go through the worker's
 * router, not here.
 */
@Singleton
public class WhatsAppGraph {

    public static final class GraphException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final Integer code;
        GraphException(String message, Integer code) {
            super(message);
            this.code = code;
        }
    }

    public record Media(String mime, byte[] bytes) {}

    static final int MEDIA_MAX = 16 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String base;
    private final String phoneNumberId;
    private final String token;

    public WhatsAppGraph(JsonMapper json,
                         @Value("${WA_GRAPH_VERSION:}") String version,
                         @Value("${WA_PHONE_NUMBER_ID:}") String phoneNumberId,
                         @Value("${WA_ACCESS_TOKEN:}") String token) {
        this.json = json;
        this.base = "https://graph.facebook.com/" + (version == null || version.isBlank() ? "v23.0" : version.strip()) + "/";
        this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.strip();
        this.token = token == null ? "" : token;
    }

    public boolean configured() {
        return !phoneNumberId.isEmpty() && !token.isEmpty();
    }

    /** @return the WhatsApp message id */
    public String sendText(String to, String text) {
        var body = new LinkedHashMap<String, Object>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "text");
        body.put("text", Map.of("preview_url", true, "body", text));
        return send(body);
    }

    /** @return the WhatsApp message id */
    public String sendTemplate(String to, String name, String language, List<String> bodyParams) {
        var template = new LinkedHashMap<String, Object>();
        template.put("name", name);
        template.put("language", Map.of("code", language));
        if (!bodyParams.isEmpty()) {
            template.put("components", List.of(Map.of("type", "body", "parameters",
                    bodyParams.stream().map(v -> Map.of("type", "text", "text", v)).toList())));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "template");
        body.put("template", template);
        return send(body);
    }

    /** A customer's photo, voice note or document, by its WhatsApp media id. */
    public Media media(String mediaId) {
        var meta = call(HttpRequest.newBuilder(URI.create(base + mediaId)).header("Authorization", "Bearer " + token).GET().build());
        if (!(meta.get("url") instanceof String url)) throw new GraphException("media without a url", null);
        try {
            var res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var in = res.body()) {
                if (res.statusCode() != 200) throw new GraphException("media download HTTP " + res.statusCode(), null);
                var bytes = in.readNBytes(MEDIA_MAX + 1);
                if (bytes.length > MEDIA_MAX) throw new GraphException("media over 16 MB", null);
                var mime = meta.get("mime_type") instanceof String m ? m : res.headers().firstValue("Content-Type").orElse("application/octet-stream");
                return new Media(mime, bytes);
            }
        } catch (IOException e) {
            throw new GraphException("media download failed: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("interrupted", null);
        }
    }

    private String send(Map<String, Object> body) {
        if (!configured()) throw new GraphException("WhatsApp is not configured (WA_PHONE_NUMBER_ID, WA_ACCESS_TOKEN)", null);
        try {
            var res = call(HttpRequest.newBuilder(URI.create(base + phoneNumberId + "/messages")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build());
            if (res.get("messages") instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> m
                    && m.get("id") instanceof String id) {
                return id;
            }
            throw new GraphException("accepted without a message id", null);
        } catch (IOException e) {
            throw new GraphException(e.getMessage(), null);
        }
    }

    private Map<String, Object> call(HttpRequest req) {
        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> parsed = res.body().length == 0 ? Map.of()
                    : json.readValue(res.body(), Argument.mapOf(String.class, Object.class));
            if (res.statusCode() / 100 != 2) {
                Integer code = null;
                String msg = "HTTP " + res.statusCode();
                if (parsed.get("error") instanceof Map<?, ?> err) {
                    if (err.get("code") instanceof Number n) code = n.intValue();
                    if (err.get("message") instanceof String s) msg = s;
                }
                throw new GraphException(msg, code);
            }
            return parsed;
        } catch (IOException e) {
            throw new GraphException(e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("interrupted", null);
        }
    }
}
