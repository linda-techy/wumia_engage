package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P2-T06: the web pixel's intake, {@code POST /pixel/events}, against Postgres. */
@MicronautTest(transactional = false)
@Property(name = "engage.pixel.write-key", value = PixelIntakeTest.KEY)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class PixelIntakeTest {

    static final String KEY = "test-pixel-key-4f1c";

    @Inject @Client("/") HttpClient client;
    @Inject DataSource dataSource;

    String clientId;

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            var rs = st.executeQuery("select current_database()");
            rs.next();
            assertTrue(rs.getString(1).endsWith("_test"), "refusing to clean " + rs.getString(1));
            st.execute("DELETE FROM events WHERE source = 'pixel'");
            st.execute("DELETE FROM checkouts WHERE token LIKE 'pxchk-%'");
        }
        clientId = "cid-" + UUID.randomUUID();
    }

    @Test
    void a_product_view_is_stored_as_a_timing_hint_without_an_identity() throws Exception {
        long identities = count("SELECT count(*) FROM identities");
        var anon = UUID.randomUUID().toString();

        assertEquals(HttpStatus.NO_CONTENT, post(KEY, view("2026-09-30T06:30:00.123Z", anon)).status());

        assertEquals("product_viewed|pixel|t|" + anon + "|8801|44581230001|linen-kurta|189900", q("""
                SELECT concat_ws('|', name, source, (identity_id IS NULL)::text::char, props->>'anon_id', props->>'product_id',
                                 props->>'variant_id', props->>'product_handle', props->>'price_paise')
                  FROM events WHERE source = 'pixel'"""));
        assertEquals(identities, count("SELECT count(*) FROM identities"), "the pixel never creates identities");
    }

    @Test
    void personal_data_in_the_body_is_never_stored() throws Exception {
        var body = view("2026-09-30T06:30:00Z", null).replace("{", """
                {"email":"priya@example.com","phone":"+919876543210",""");

        post(KEY, body);

        assertEquals("0", q("""
                SELECT count(*) FROM events WHERE source = 'pixel'
                   AND (props::text LIKE '%priya%' OR props::text LIKE '%98765%')"""));
        assertEquals("1", q("SELECT count(*) FROM events WHERE source = 'pixel'"));
    }

    @Test
    void a_replayed_event_is_stored_once() throws Exception {
        post(KEY, view("2026-09-30T06:30:00Z", null));
        post(KEY, view("2026-09-30T06:30:00Z", null));

        assertEquals("1", q("SELECT count(*) FROM events WHERE source = 'pixel'"));
    }

    @Test
    void a_checkout_step_moves_last_step_forward_and_a_late_one_cannot_move_it_back() throws Exception {
        var token = "pxchk-" + UUID.randomUUID();
        exec("INSERT INTO checkouts (token) VALUES ('" + token + "')");

        post(KEY, step("checkout_shipping_info_submitted", token, "2026-09-30T06:31:00Z"));
        post(KEY, step("payment_info_submitted", token, "2026-09-30T06:33:00Z"));
        post(KEY, step("checkout_contact_info_submitted", token, "2026-09-30T06:30:00Z"));   // arrives last

        assertEquals("payment_info_submitted", q("SELECT last_step FROM checkouts WHERE token = '" + token + "'"));
        assertEquals("3", q("SELECT count(*) FROM events WHERE source = 'pixel'"), "every step is kept as an event");
    }

    @Test
    void a_step_for_an_unknown_checkout_is_kept_as_an_event_only() throws Exception {
        assertEquals(HttpStatus.NO_CONTENT,
                post(KEY, step("checkout_started", "pxchk-none-" + UUID.randomUUID(), "2026-09-30T06:30:00Z")).status());
        assertEquals("1", q("SELECT count(*) FROM events WHERE source = 'pixel'"));
    }

    @Test
    void a_wrong_or_missing_key_is_refused_and_nothing_stored() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(() -> post("wrong", view("2026-09-30T06:30:00Z", null))));
        assertEquals(HttpStatus.UNAUTHORIZED, status(() -> post(null, view("2026-09-30T06:30:00Z", null))));
        assertEquals("0", q("SELECT count(*) FROM events WHERE source = 'pixel'"));
    }

    @Test
    void only_the_listed_events_with_their_required_fields_are_accepted() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> post(KEY, view("2026-09-30T06:30:00Z", null)
                .replace("product_viewed", "search_submitted"))));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> post(KEY, view("yesterday", null))));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> post(KEY, step("payment_info_submitted", null, "2026-09-30T06:30:00Z"))));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> post(KEY, view("2026-09-30T06:30:00Z", null)
                .replace("\"8801\"", "\"gid://shopify/Product/8801\""))));
    }

    @Test
    void one_browser_is_limited_to_300_events_an_hour() throws Exception {
        for (int i = 0; i < 300; i++) {
            post(KEY, view("2026-09-30T06:%02d:%02dZ".formatted(i / 60, i % 60), null));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status(() -> post(KEY, view("2026-09-30T07:00:00Z", null))));
        assertEquals("300", q("SELECT count(*) FROM events WHERE source = 'pixel'"));
    }

    @Test
    void the_sandboxed_pixel_may_call_it_cross_origin() {
        MutableHttpRequest<?> preflight = HttpRequest.create(HttpMethod.OPTIONS, "/pixel/events")
                .header("Origin", "null")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-engage-key");
        var res = client.toBlocking().exchange(preflight);

        assertEquals(HttpStatus.OK, res.status());
        assertEquals("null", res.getHeaders().get("Access-Control-Allow-Origin"));
        assertTrue(String.valueOf(res.getHeaders().getAll("Access-Control-Allow-Headers")).toLowerCase().contains("x-engage-key"),
                () -> res.getHeaders().asMap().toString());
    }

    /* -------------------------------- helpers -------------------------------- */

    String view(String at, String anon) {
        return """
            {"name":"product_viewed","clientId":"%s","at":"%s","anonId":%s,"productId":"8801",
             "variantId":"44581230001","productTitle":"Linen Kurta","productHandle":"linen-kurta","pricePaise":189900}"""
                .formatted(clientId, at, anon == null ? "null" : "\"" + anon + "\"");
    }

    String step(String name, String token, String at) {
        return """
            {"name":"%s","clientId":"%s","at":"%s","checkoutToken":%s,"totalPaise":189900}"""
                .formatted(name, clientId, at, token == null ? "null" : "\"" + token + "\"");
    }

    io.micronaut.http.HttpResponse<?> post(String key, String json) {
        var req = HttpRequest.POST("/pixel/events", json).contentType(MediaType.APPLICATION_JSON);
        if (key != null) req.header("X-Engage-Key", key);
        return client.toBlocking().exchange(req);
    }

    static HttpStatus status(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    long count(String sql) throws SQLException {
        return Long.parseLong(q(sql));
    }

    void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    String q(String sql) {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
