package in.brand.engage.admin.segments;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.admin.auth.LoginRateLimit;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P6-T05 segments against fixture data, through the API. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class SegmentTest {

    /** Phase-6 §3, with product type for collection: collection membership is not ingested yet. */
    static final Map<String, Object> SOUTH_ETHNIC_LAPSED = Map.of("all", List.of(
            Map.of("field", "bought_product_type", "op", "in", "value", List.of("kurta", "ethnic set")),
            Map.of("field", "last_order_at", "op", "older_than", "value", "P60D"),
            Map.of("field", "state", "op", "in", "value", List.of("KL", "TN", "KA"))));

    static final AtomicInteger SEQ = new AtomicInteger();

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject LoginRateLimit loginLimit;
    @Inject Db db;

    String editor;

    @BeforeEach void reset() {
        data.cleanAdminTables();       // operators CASCADE: segments too
        data.truncateCustomerTables(); // identities CASCADE: orders, order_lines, carts, consents
        db.inTx(c -> Sql.update(c, "TRUNCATE products, channel_capability CASCADE"));
        loginLimit.reset();
        editor = data.loginAsCampaignEditor(client);
        product("p-kurta", "Kurta");
        product("p-set", "Ethnic Set");
        product("p-dress", "Dress");
    }

    int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    long preview(Object definition) {
        var out = client.toBlocking().retrieve(HttpRequest.POST("/api/segments/preview", Map.of("definition", definition))
                .bearerAuth(editor), Map.class);
        return ((Number) out.get("size")).longValue();
    }

    List<UUID> members(Object definition) {
        var q = SegmentCompiler.identities(SegmentDsl.parse(definition));
        return db.inTx(c -> {
            var out = new ArrayList<UUID>();
            try (var ps = Sql.prepare(c, q.sql(), q.params().toArray()); var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class));
            }
            return out;
        });
    }

    @Test void the_phase_6_example_finds_lapsed_southern_ethnicwear_buyers_and_no_one_else() {
        var match = customer("KL");
        order(match, 90, "p-kurta", "M", false);
        var recent = customer("KL");
        order(recent, 90, "p-kurta", "M", false);
        order(recent, 10, "p-dress", "M", false);            // bought anything recently: not lapsed
        var north = customer("MH");
        order(north, 90, "p-set", "L", false);
        var dress = customer("TN");
        order(dress, 90, "p-dress", "S", false);
        var cancelled = customer("KA");
        order(cancelled, 90, "p-kurta", "M", true);          // a cancelled order is not a purchase
        customer("KL");                                      // never ordered

        assertEquals(List.of(match), members(SOUTH_ETHNIC_LAPSED));
        assertEquals(1, preview(SOUTH_ETHNIC_LAPSED));
    }

    @Test void size_consent_capability_and_carts_are_predicates_too() {
        var a = customer("KL");
        order(a, 5, "p-kurta", "M / Red", false);
        consent(a, "whatsapp");
        db.inTx(c -> Sql.update(c, """
                INSERT INTO channel_capability (identity_id, channel, state) VALUES (?, 'whatsapp', 'CAPABLE')""", a));
        var b = customer("KL");
        order(b, 5, "p-kurta", "XL / Red", false);
        db.inTx(c -> Sql.update(c, """
                INSERT INTO carts (cart_token, identity_id, item_count, total_paise) VALUES ('cart-b', ?, 2, 349900)""", b));

        assertEquals(List.of(a), members(Map.of("field", "bought_size", "op", "in", "value", List.of("m"))));
        assertEquals(List.of(a), members(Map.of("all", List.of(
                Map.of("field", "wa_marketing", "op", "is", "value", true),
                Map.of("field", "wa_capable", "op", "is", "value", "CAPABLE")))));
        assertEquals(List.of(b), members(Map.of("field", "wa_capable", "op", "is", "value", "UNKNOWN")),
                "no capability row is UNKNOWN");
        assertEquals(List.of(b), members(Map.of("all", List.of(
                Map.of("field", "has_open_cart", "op", "is", "value", true),
                Map.of("field", "cart_value", "op", "gte", "value", 3000)))));
        assertEquals(List.of(b), members(Map.of("not", Map.of("field", "wa_marketing", "op", "is", "value", true))));
    }

    @Test void a_saved_segment_is_sized_audited_and_unique_by_name() {
        customer("KL");
        var created = client.toBlocking().retrieve(HttpRequest.POST("/api/segments", Map.of(
                "name", "Onam: lapsed ethnic buyers", "definition", SOUTH_ETHNIC_LAPSED)).bearerAuth(editor), Map.class);
        assertEquals(0, ((Number) created.get("lastSize")).intValue());
        assertEquals(SOUTH_ETHNIC_LAPSED.get("all"), ((Map<String, Object>) created.get("definition")).get("all"));
        assertEquals(1L, data.countAudit("segment.create"));

        assertEquals(409, status(() -> client.toBlocking().exchange(HttpRequest.POST("/api/segments", Map.of(
                "name", "onam: LAPSED ethnic buyers", "definition", SOUTH_ETHNIC_LAPSED)).bearerAuth(editor))));

        var id = (String) created.get("id");
        var narrower = Map.of("field", "state", "op", "in", "value", List.of("KL"));
        var updated = client.toBlocking().retrieve(HttpRequest.PUT("/api/segments/" + id, Map.of(
                "name", "Kerala", "definition", narrower)).bearerAuth(editor), Map.class);
        assertEquals(1, ((Number) updated.get("lastSize")).intValue());
        assertEquals(1L, data.countAudit("segment.update"));

        var viewer = data.loginAsViewer(client);
        var all = client.toBlocking().retrieve(HttpRequest.GET("/api/segments").bearerAuth(viewer), Argument.listOf(Map.class));
        assertEquals(List.of("Kerala"), all.stream().map(s -> s.get("name")).toList());
    }

    @Test void viewers_read_segments_but_cannot_count_or_save_them() {
        var viewer = data.loginAsViewer(client);
        var def = Map.of("field", "state", "op", "in", "value", List.of("KL"));
        assertEquals(403, status(() -> client.toBlocking().exchange(
                HttpRequest.POST("/api/segments/preview", Map.of("definition", def)).bearerAuth(viewer))));
        assertEquals(403, status(() -> client.toBlocking().exchange(
                HttpRequest.POST("/api/segments", Map.of("name", "x", "definition", def)).bearerAuth(viewer))));
        var fields = client.toBlocking().retrieve(HttpRequest.GET("/api/segments/fields").bearerAuth(viewer),
                Argument.listOf(Map.class));
        var collection = fields.stream().filter(f -> "bought_collection".equals(f.get("name"))).findFirst().orElseThrow();
        assertEquals(false, collection.get("available"));
        assertNotNull(collection.get("note"));
    }

    @Test void a_bad_definition_is_400_and_an_injected_value_just_matches_nobody() {
        customer("KL");
        assertEquals(400, status(() -> preview(Map.of("field", "password_hash", "op", "in", "value", List.of("x")))));
        assertEquals(0, preview(Map.of("field", "state", "op", "in", "value", List.of("KL'); DELETE FROM identities; --"))));
        assertEquals(1, preview(Map.of("field", "state", "op", "in", "value", List.of("KL"))), "nothing was deleted");
    }

    /* -------------------------------- fixtures -------------------------------- */

    UUID customer(String state) {
        int n = SEQ.incrementAndGet();
        var id = UUID.fromString(data.createIdentity("c" + n + "@example.com", "9198" + String.format("%08d", n)));
        db.inTx(c -> Sql.update(c, """
                UPDATE profiles SET attrs = attrs || jsonb_build_object('state', ?::text) WHERE identity_id = ?""", state, id));
        return id;
    }

    void product(String id, String type) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO products (product_id, handle, product_type, size_position, updated_at)
                VALUES (?, ?, lower(?), 1, now())""", id, id, type));
    }

    void order(UUID identity, int daysAgo, String productId, String variantTitle, boolean cancelled) {
        var orderId = "o-" + SEQ.incrementAndGet();
        db.inTx(c -> {
            Sql.update(c, """
                    INSERT INTO orders (id, order_number, identity_id, total_paise, created_at, cancelled_at)
                    VALUES (?, ?, ?, 129900, now() - make_interval(days => ?),
                            CASE WHEN ? THEN now() END)""", orderId, "#" + orderId, identity, daysAgo, cancelled);
            Sql.update(c, """
                    INSERT INTO order_lines (order_id, line_no, product_id, variant_title, quantity, price_paise)
                    VALUES (?, 1, ?, ?, 1, 129900)""", orderId, productId, variantTitle);
            return null;
        });
    }

    void consent(UUID identity, String channel) {
        db.inTx(c -> Sql.update(c, """
                INSERT INTO consents (identity_id, channel, purpose, state, source)
                VALUES (?, CAST(? AS channel), 'marketing', 'granted', 'test')""", identity, channel));
    }
}
