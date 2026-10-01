package in.brand.engage.admin.payments;

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

/** Task 11: GET /api/payments/failures against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class PaymentsTest {

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
    }

    @Test void reports_a_failed_attempt_with_its_match_method_and_money_in_paise() {
        var identityId = data.createIdentity("mary@example.com", "918606572870");
        data.insertFailedPayment(identityId, "pay_test123", 6103L, "upi", "customer", "payment_cancelled",
                "phone_amount_window", "chk_abc");

        var token = data.loginAsViewer(client);
        var body = client.toBlocking().retrieve(HttpRequest.GET("/api/payments/failures").bearerAuth(token), Map.class);

        var recent = (List<Map<String, Object>>) body.get("recent");
        assertEquals(1, recent.size());
        assertEquals(6103, ((Number) recent.getFirst().get("amountPaise")).longValue());
        assertEquals("phone_amount_window", recent.getFirst().get("matchMethod"));
        assertEquals(Boolean.TRUE, recent.getFirst().get("joinedToCheckout"));
        assertFalse(body.toString().contains("8606572870"), "no contact details on this screen");

        var report = (List<Map<String, Object>>) body.get("report");
        assertEquals(1, ((Number) report.getFirst().get("failures")).intValue());
        assertEquals("phone_amount_window", report.getFirst().get("matchMethod"));
    }

    @Test void requires_sign_in() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/payments/failures"), Map.class));
        assertEquals(401, e.getStatus().getCode());
    }
}
