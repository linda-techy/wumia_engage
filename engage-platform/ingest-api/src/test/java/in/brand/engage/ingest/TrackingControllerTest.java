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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P4-T08: the Track link in order updates, through the storefront proxy. */
@MicronautTest(transactional = false)
@Property(name = "micronaut.http.client.follow-redirects", value = "false")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class TrackingControllerTest {

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

    long shipment(String trackingUrl) throws Exception {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement("""
                INSERT INTO shipments (order_id, carrier, tracking_url, status, status_at)
                VALUES ('track-test', 'Delhivery', ?, 'in_transit', now()) RETURNING id""")) {
            ps.setString(1, trackingUrl);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test void a_signed_ref_redirects_to_the_couriers_page() throws Exception {
        var res = get(PushEngagementTest.signedUri("/t/" + refs.of(shipment("https://track.example/AWB123"))));
        assertEquals(HttpStatus.SEE_OTHER.getCode() / 100, res.code() / 100, "a redirect, got " + res.code());
        assertEquals("https://track.example/AWB123", res.getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test void without_a_courier_page_yet_the_store_shows_a_note() throws Exception {
        var res = get(PushEngagementTest.signedUri("/t/" + refs.of(shipment(null))));
        assertEquals(HttpStatus.OK, res.getStatus());
        assertTrue(res.getContentType().orElseThrow().toString().startsWith("application/liquid"));
        assertTrue(res.body().contains("once the courier has scanned it"));
    }

    @Test void a_walked_ref_is_not_found_and_an_unsigned_request_is_refused() throws Exception {
        long id = shipment("https://track.example/AWB124");
        var forged = Long.toString(id + 1, 36) + refs.of(id).substring(refs.of(id).indexOf('-'));
        assertEquals(HttpStatus.NOT_FOUND, get(PushEngagementTest.signedUri("/t/" + forged)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/shopify/proxy/t/" + refs.of(id)).getStatus());
    }
}
