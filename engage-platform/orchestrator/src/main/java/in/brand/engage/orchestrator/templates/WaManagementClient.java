package in.brand.engage.orchestrator.templates;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The WhatsApp Business Management API for one WhatsApp Business Account
 * (P4-T03): the templates Meta holds and their verdicts, the phone numbers
 * and their quality, and submitting a template. Not a channel adapter: it
 * never sends a message. Registered only when {@code WA_BUSINESS_ACCOUNT_ID}
 * is set.
 */
@Singleton
@Requires(property = "engage.whatsapp.business-account-id", pattern = ".+")
public class WaManagementClient {

    /** Meta's view of one template locale. {@code quality} is GREEN, YELLOW, RED or UNKNOWN. */
    public record MetaTemplate(String id, String name, String language, String status, String category,
                               String quality, String rejectedReason) {}

    public record MetaNumber(String id, String displayNumber, String verifiedName, String quality, String tier) {}

    public static final class GraphException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public GraphException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String base;
    private final String waba;
    private final String token;

    public WaManagementClient(JsonMapper json,
                              @Value("${engage.whatsapp.graph-base:`https://graph.facebook.com`}") String graphBase,
                              @Value("${engage.whatsapp.graph-version:v23.0}") String version,
                              @Value("${engage.whatsapp.business-account-id}") String waba,
                              @Value("${engage.whatsapp.access-token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("WA_BUSINESS_ACCOUNT_ID is set but WA_ACCESS_TOKEN is not.");
        }
        var v = version == null || version.isBlank() ? "v23.0" : version.strip();
        this.json = json;
        this.base = graphBase.replaceAll("/+$", "") + "/" + v + "/";
        this.waba = waba.strip();
        this.token = token;
    }

    public String businessAccountId() {
        return waba;
    }

    public List<MetaTemplate> templates() {
        var out = new ArrayList<MetaTemplate>();
        String url = base + waba + "/message_templates?limit=100&fields="
                + enc("id,name,language,status,category,quality_score,rejected_reason");
        while (url != null) {
            var page = get(url);
            if (page.get("data") instanceof List<?> data) {
                for (var d : data) {
                    if (!(d instanceof Map<?, ?> t)) continue;
                    var quality = t.get("quality_score") instanceof Map<?, ?> q && q.get("score") != null
                            ? String.valueOf(q.get("score")) : "UNKNOWN";
                    out.add(new MetaTemplate(str(t.get("id")), str(t.get("name")), str(t.get("language")), str(t.get("status")),
                            str(t.get("category")), quality.toUpperCase(Locale.ROOT), str(t.get("rejected_reason"))));
                }
            }
            url = page.get("paging") instanceof Map<?, ?> p && p.get("next") instanceof String next ? next : null;
        }
        return out;
    }

    public List<MetaNumber> phoneNumbers() {
        var page = get(base + waba + "/phone_numbers?fields="
                + enc("id,display_phone_number,verified_name,quality_rating,messaging_limit_tier"));
        var out = new ArrayList<MetaNumber>();
        if (page.get("data") instanceof List<?> data) {
            for (var d : data) {
                if (d instanceof Map<?, ?> n) {
                    out.add(new MetaNumber(str(n.get("id")), str(n.get("display_phone_number")), str(n.get("verified_name")),
                            n.get("quality_rating") == null ? "UNKNOWN" : str(n.get("quality_rating")).toUpperCase(Locale.ROOT),
                            str(n.get("messaging_limit_tier"))));
                }
            }
        }
        return out;
    }

    /** Submits one template for review. @return Meta's response ({@code id}, {@code status}, {@code category}) */
    public Map<String, Object> create(Map<String, Object> template) {
        try {
            var req = HttpRequest.newBuilder(URI.create(base + waba + "/message_templates"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(template)))
                    .build();
            return call(req);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> get(String url) {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).GET().build();
        return call(req);
    }

    private Map<String, Object> call(HttpRequest req) {
        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> body = res.body().length == 0 ? Map.of()
                    : json.readValue(res.body(), Argument.mapOf(String.class, Object.class));
            if (res.statusCode() / 100 != 2) {
                var msg = body.get("error") instanceof Map<?, ?> err ? err.get("code") + " " + err.get("message") : "HTTP " + res.statusCode();
                throw new GraphException("Graph API " + req.method() + " " + req.uri().getPath() + " failed: " + msg);
            }
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("interrupted");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
