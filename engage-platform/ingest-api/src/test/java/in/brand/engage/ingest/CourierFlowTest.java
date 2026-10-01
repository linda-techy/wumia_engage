package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.crypto.Hmacs;
import in.brand.engage.ingest.courier.ShipmentStatus;
import in.brand.engage.ingest.courier.ShiprocketAdapter;
import in.brand.engage.ingest.inbox.InboxProcessor;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * P1-T02: Shopify fulfillments and Shiprocket tracking webhooks into
 * shipments, in natural, reversed and duplicate orderings (invariant 11).
 * Courier times in the fixtures: in transit 20 Sep 16:40, out for delivery
 * 21 Sep 09:15, undelivered 21 Sep 18:40, delivered 22 Sep 12:30 (IST).
 */
@MicronautTest(transactional = false)
@Property(name = "engage.courier.webhook-token", value = CourierFlowTest.TOKEN)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class CourierFlowTest {

    static final String TOKEN = "test-courier-token-91b3";
    static final String AWB = "19041424751540";
    static final Path FX = Path.of("src/test/resources/fixtures");

    @Inject @Client("/") HttpClient client;
    @Inject InboxProcessor processor;
    @Inject DataSource dataSource;
    @Inject ShiprocketAdapter shiprocket;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("""
                TRUNCATE webhook_inbox, events, orders, shipment_events, shipments, pending_optins, conversions,
                         checkouts, carts, consents, identity_keys, profiles, identities CASCADE""");
        }
    }

    @Test
    void natural_order_fulfillment_then_courier_scans_ends_delivered_with_one_event_each() throws Exception {
        shopify("orders/create", "shopify_order_create.json");
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        courier("in_transit");
        courier("ofd");
        courier("delivered");

        assertEquals("delivered|6012345678901|1", q("SELECT concat_ws('|', status, order_id, count(*) OVER ()) FROM shipments"));
        assertEquals("order_delivered,order_shipped,out_for_delivery", q("""
                SELECT string_agg(name, ',' ORDER BY name) FROM events
                 WHERE name IN ('order_shipped','out_for_delivery','order_delivered','delivery_failed')"""));
        assertEquals("0", q("""
                SELECT count(*) FROM events WHERE name IN ('order_shipped','out_for_delivery','order_delivered')
                   AND (identity_id IS NULL OR props->>'awb' <> '%s' OR props->>'order_id' <> '6012345678901')""".formatted(AWB)),
                "every event names the buyer, the order and the AWB");
    }

    @Test
    void courier_scans_before_the_fulfillment_attach_to_it_when_it_lands() throws Exception {
        courier("ofd");
        assertEquals("out_for_delivery|", q("SELECT concat_ws('|', status, COALESCE(order_id, '')) FROM shipments"));

        shopify("fulfillments/create", "shopify_fulfillment_create.json");

        assertEquals("1", q("SELECT count(*) FROM shipments"), "one parcel, one row");
        assertEquals("out_for_delivery|6012345678901|5123456789012",
                q("SELECT concat_ws('|', status, order_id, fulfillment_id) FROM shipments"));
    }

    @Test
    void delivered_before_out_for_delivery_stays_delivered() throws Exception {
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        courier("delivered");
        courier("ofd");                                          // older scan, arrives late
        courier("in_transit");

        assertEquals("delivered", q("SELECT status FROM shipments"));
        assertEquals("0", q("SELECT count(*) FROM events WHERE name = 'out_for_delivery'"), "a stale scan emits nothing");
        assertEquals("3", q("SELECT count(*) FROM shipment_events WHERE status <> 'created'"), "but every scan is kept");
    }

    @Test
    void a_late_in_transit_scan_does_not_move_out_for_delivery_back() throws Exception {
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        courier("ofd");
        courier("in_transit");                                   // scanned the day before, delivered to us after

        assertEquals("out_for_delivery", q("SELECT status FROM shipments"));
        assertEquals("2026-09-21 03:45:00+00", q("SELECT to_char(status_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' FROM shipments"));
    }

    @Test
    void the_same_scan_twice_is_one_row_and_one_event() throws Exception {
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        courier("ofd");
        courier("ofd");                                          // identical retry: the inbox dedupes it
        var reformatted = Files.readString(FX.resolve("courier_shiprocket_ofd.json")).replace("\n", "\n  ");
        post(reformatted, TOKEN);                                // same scan, different bytes

        assertEquals("1", q("SELECT count(*) FROM shipment_events WHERE status = 'out_for_delivery'"));
        assertEquals("1", q("SELECT count(*) FROM events WHERE name = 'out_for_delivery'"));
    }

    @Test
    void each_failed_attempt_counts_and_a_reattempt_can_still_deliver() throws Exception {
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        courier("ofd");
        courier("ndr");
        post(Files.readString(FX.resolve("courier_shiprocket_ndr.json"))
                .replace("21 09 2026 18:40:00", "22 09 2026 10:05:00"), TOKEN);   // second attempt fails too

        assertEquals("ndr|2|Undelivered - Customer not available",
                q("SELECT concat_ws('|', status, ndr_attempts, ndr_reason) FROM shipments"));
        assertEquals("2", q("SELECT count(*) FROM events WHERE name = 'delivery_failed'"));

        courier("delivered");
        assertEquals("delivered", q("SELECT status FROM shipments"));
    }

    @Test
    void a_cancelled_fulfillment_cancels_the_shipment() throws Exception {
        shopify("fulfillments/create", "shopify_fulfillment_create.json");
        var cancelled = Files.readString(FX.resolve("shopify_fulfillment_create.json"))
                .replace("\"status\": \"success\"", "\"status\": \"cancelled\"")
                .replace("\"updated_at\": \"2026-09-20T09:30:00+05:30\"", "\"updated_at\": \"2026-09-20T11:00:00+05:30\"");
        shopifyBody("fulfillments/update", cancelled);

        assertEquals("cancelled", q("SELECT status FROM shipments"));
        courier("delivered");
        assertEquals("cancelled", q("SELECT status FROM shipments"), "terminal statuses are never left");
    }

    @Test
    void a_wrong_or_missing_token_is_refused_and_nothing_stored() throws Exception {
        var body = Files.readString(FX.resolve("courier_shiprocket_ofd.json"));
        assertEquals(HttpStatus.UNAUTHORIZED, post(body, "wrong"));
        assertEquals(HttpStatus.UNAUTHORIZED, post(body, null));
        assertEquals("0", q("SELECT count(*) FROM webhook_inbox WHERE source = 'courier'"));
    }

    @Test
    void shiprocket_labels_and_times_map_onto_the_seven_statuses() {
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shiprocket.status("OUT FOR DELIVERY", "17"));
        assertEquals(ShipmentStatus.NDR, shiprocket.status("UNDELIVERED", "21"));
        assertEquals(ShipmentStatus.DELIVERED, shiprocket.status("Delivered", "7"));
        assertEquals(ShipmentStatus.RTO, shiprocket.status("RTO DELIVERED", "10"));
        assertEquals(ShipmentStatus.RTO, shiprocket.status("RTO_INITIATED", "9"));
        assertEquals(ShipmentStatus.IN_TRANSIT, shiprocket.status("PICKED UP", "42"));
        assertEquals(ShipmentStatus.CANCELLED, shiprocket.status("CANCELED", "8"));
        assertNull(shiprocket.status("PICKUP SCHEDULED", "13"), "pre-pickup states change nothing");
        assertNull(shiprocket.status("LOST", "12"), "lost is a human's call");
        assertEquals(ShipmentStatus.IN_TRANSIT, shiprocket.status("", "6"), "no label: the confirmed ids");
        assertEquals(OffsetDateTime.parse("2023-05-23T11:43:52+05:30"), shiprocket.time("23 05 2023 11:43:52"));
        assertEquals(OffsetDateTime.parse("2023-05-19T11:59:16+05:30"), shiprocket.time("2023-05-19 11:59:16"));
        assertNull(shiprocket.time("yesterday"));
    }

    /* -------------------------------- helpers -------------------------------- */

    void courier(String name) throws Exception {
        assertEquals(HttpStatus.OK, post(Files.readString(FX.resolve("courier_shiprocket_" + name + ".json")), TOKEN));
    }

    HttpStatus post(String json, String token) {
        var req = HttpRequest.POST("/webhooks/courier", json.getBytes(StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) req.header("x-api-key", token);
        try {
            var status = client.toBlocking().exchange(req).status();
            processor.drain();
            return status;
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    void shopify(String topic, String fixture) throws Exception {
        shopifyBody(topic, Files.readString(FX.resolve(fixture)));
    }

    void shopifyBody(String topic, String json) {
        var body = json.getBytes(StandardCharsets.UTF_8);
        client.toBlocking().exchange(HttpRequest.POST("/webhooks/shopify", body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Webhook-Id", UUID.randomUUID().toString())
                .header("X-Shopify-Shop-Domain", System.getenv("SHOPIFY_SHOP_DOMAIN"))
                .header("X-Shopify-Hmac-Sha256", Hmacs.sha256Base64(System.getenv("SHOPIFY_API_SECRET"), body)));
        processor.drain();
    }

    String q(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
