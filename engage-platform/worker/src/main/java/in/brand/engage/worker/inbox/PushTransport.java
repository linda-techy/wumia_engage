package in.brand.engage.worker.inbox;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One Web Push request with no payload (RFC 8030). Tests fake it. */
public interface PushTransport {

    /** @return the push service's HTTP status (201 accepted, 404/410 gone) */
    int post(String endpoint, String authorization, int ttlSeconds, String topic) throws IOException, InterruptedException;

    @Singleton
    class Http implements PushTransport {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        @Override
        public int post(String endpoint, String authorization, int ttlSeconds, String topic) throws IOException, InterruptedException {
            var req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization)
                    .header("TTL", String.valueOf(ttlSeconds))
                    .header("Urgency", "high")
                    .header("Topic", topic)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
