package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.TrackRef;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P5-T02: the short pay link in the payment-failed SMS, through the storefront proxy. */
@MicronautTest(transactional = false)
@Property(name = "micronaut.http.client.follow-redirects", value = "false")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PayLinkControllerTest {

    @Inject @Client("/") HttpClient client;
    @Inject TrackRef refs;
    @Inject DataSource dataSource;

    @SuppressWarnings("unchecked")
    HttpResponse<String> get(String uri) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(uri), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    long run(String intent, String payPath) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            var identity = UUID.randomUUID();
            try (var ps = c.prepareStatement("INSERT INTO identities (id) VALUES (?)")) {
                ps.setObject(1, identity);
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO cascade_runs (intent_key, identity_id, subject_key, priority, vars)
                    VALUES (?, ?, ?, 1, jsonb_build_object('pay_path', ?::text)) RETURNING id""")) {
                ps.setString(1, intent);
                ps.setObject(2, identity);
                ps.setString(3, "test-" + UUID.randomUUID());
                ps.setString(4, payPath);
                var rs = ps.executeQuery();
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test void a_signed_pay_ref_opens_the_saved_checkout_on_the_store() throws Exception {
        long id = run("payment_failed", "checkouts/cn/hWN4abc/recover?key=k123");
        var res = get(PushEngagementTest.signedUri("/pay/" + refs.ofPayment(id)));
        assertEquals(3, res.code() / 100, "a redirect, got " + res.code());
        assertEquals("/checkouts/cn/hWN4abc/recover?key=k123", res.getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test void an_unsafe_path_falls_back_to_the_cart() throws Exception {
        long id = run("payment_failed", "//evil.example/steal");
        assertEquals("/cart", get(PushEngagementTest.signedUri("/pay/" + refs.ofPayment(id))).getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test void a_tracking_ref_a_walked_ref_or_another_journey_is_not_a_pay_link() throws Exception {
        long id = run("payment_failed", "cart");
        assertEquals(HttpStatus.NOT_FOUND, get(PushEngagementTest.signedUri("/pay/" + refs.of(id))).getStatus(), "tracking ref");
        long other = run("winback", "collections/all");
        assertEquals("/cart", get(PushEngagementTest.signedUri("/pay/" + refs.ofPayment(other))).getHeaders().get(HttpHeaders.LOCATION),
                "not a payment run: nothing of it is used");
        assertEquals(HttpStatus.UNAUTHORIZED, get("/shopify/proxy/pay/" + refs.ofPayment(id)).getStatus(), "unsigned");
    }
}
