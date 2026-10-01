package in.brand.engage.admin.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Task 9: GET /api/ingest/health against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class IngestHealthTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateInbox();
    }

    @Test void requires_authentication() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/ingest/health"), Map.class));
        assertEquals(401, e.getStatus().getCode());
    }

    @SuppressWarnings("unchecked")
    @Test void counts_pending_and_failed_by_topic() {
        data.insertInboxRow("shopify", "d1", "orders/create", true, null);
        data.insertInboxRow("shopify", "d2", "orders/create", false, null);
        data.insertInboxRow("shopify", "d3", "orders/create", false, "boom");
        data.insertInboxRow("razorpay", "d4", "payment.failed", true, null);

        var token = data.loginAsViewer(client);
        var body = client.toBlocking().retrieve(HttpRequest.GET("/api/ingest/health").bearerAuth(token), Map.class);

        var topics = (List<Map<String, Object>>) body.get("topics");
        var orders = topics.stream().filter(t -> "orders/create".equals(t.get("topic"))).findFirst().orElseThrow();
        assertEquals(3, ((Number) orders.get("total")).intValue());
        assertEquals(2, ((Number) orders.get("pending")).intValue());
        assertEquals(1, ((Number) orders.get("failed")).intValue());
        assertEquals(2, topics.size(), "one row per source and topic");

        var stuck = (List<Map<String, Object>>) body.get("stuck");
        assertEquals(1, stuck.size());
        assertEquals("boom", stuck.getFirst().get("lastError"));
        assertEquals("d3", stuck.getFirst().get("deliveryId"));
    }
}
