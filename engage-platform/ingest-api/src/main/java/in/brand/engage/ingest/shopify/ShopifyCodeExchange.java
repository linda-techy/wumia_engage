package in.brand.engage.ingest.shopify;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** The last step of Shopify's authorization: a one-time code for the store's offline token. Tests fake it. */
public interface ShopifyCodeExchange {

    record Grant(String token, String scopes) {}

    final class Failed extends Exception {
        private static final long serialVersionUID = 1L;

        public Failed(String message) {
            super(message);
        }
    }

    Grant exchange(String shop, String code) throws Failed;

    @Singleton
    class Http implements ShopifyCodeExchange {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final JsonMapper json;
        private final String clientId;
        private final String apiSecret;

        @Inject
        public Http(JsonMapper json, @Value("${engage.shopify.client-id:}") String clientId,
                    @Value("${engage.shopify.api-secret:}") String apiSecret) {
            this.json = json;
            this.clientId = clientId;
            this.apiSecret = apiSecret;
        }

        @Override
        public Grant exchange(String shop, String code) throws Failed {
            try {
                var body = json.writeValueAsBytes(Map.of("client_id", clientId, "client_secret", apiSecret, "code", code));
                var res = http.send(HttpRequest.newBuilder(URI.create("https://" + shop + "/admin/oauth/access_token"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) throw new Failed("HTTP " + res.statusCode());
                Map<String, Object> node = json.readValue(res.body(), Argument.mapOf(String.class, Object.class));
                if (!(node.get("access_token") instanceof String token) || token.isBlank()) throw new Failed("no access_token in the response");
                return new Grant(token, node.get("scope") instanceof String s ? s : "");
            } catch (IOException e) {
                throw new Failed(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failed("interrupted");
            }
        }
    }
}
