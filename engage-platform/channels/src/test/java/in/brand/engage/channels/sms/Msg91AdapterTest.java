package in.brand.engage.channels.sms;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.SmsCall;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P4-T06 against a fake MSG91 Flow API. */
class Msg91AdapterTest {

    record Reply(int status, String body) {}

    static final String DLT_ID = "1107170000000000001";
    static final String BODY = "Your WUMIKA order {#var#} has shipped with {#var#}. Track: https://wumika.com/apps/track/{#var#} -WUMIKA";

    HttpServer server;
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> keys = new CopyOnWriteArrayList<>();
    Msg91Adapter adapter;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/flow", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(ex.getRequestHeaders().getFirst("authkey"));
            var r = replies.isEmpty() ? new Reply(500, "") : replies.poll();
            var bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        adapter = new Msg91Adapter(ObjectMapper.getDefault(), "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 0);
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    static RenderedMessage sms(List<String> values) {
        return new RenderedMessage(Channel.SMS, "sms_order_shipped", 42, "order_shipped", "", "", "", null, null,
                Duration.ofHours(12), false, null, null,
                new SmsCall(DLT_ID, "64f0c0ffee", BODY, List.of("order_no", "courier", "track_ref").subList(0, Math.min(3, values.size())),
                        values));
    }

    static Addresses to(String phone) {
        return new Addresses(List.of(), phone, null);
    }

    @Test void a_send_posts_the_template_and_named_variables_and_keeps_the_request_id() throws Exception {
        replies.add(new Reply(200, "{\"type\":\"success\",\"message\":\"3763646c3058313530393138\"}"));

        var result = adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("919876543210"));

        assertEquals("3763646c3058313530393138", result.providerId());
        assertEquals("test-key", keys.getFirst());
        var body = requests.getFirst();
        assertTrue(body.contains("\"template_id\":\"64f0c0ffee\""), body);
        assertTrue(body.contains("\"mobiles\":\"919876543210\""), body);
        assertTrue(body.contains("\"order_no\":\"#1042\"") && body.contains("\"track_ref\":\"1042\""), body);
        assertTrue(body.contains("\"short_url\":\"0\""), body);
    }

    @Test void a_wrong_variable_count_fails_permanently_without_an_http_call() {
        var e = assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery")), to("919876543210")));
        assertEquals("dlt_mismatch", e.code());
        assertFalse(e.suppress());
        assertTrue(requests.isEmpty(), "nothing went to MSG91");
    }

    @Test void a_value_over_30_characters_fails_permanently_without_an_http_call() {
        var e = assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "A courier with a very long name Ltd", "1042")), to("919876543210")));
        assertEquals("dlt_mismatch", e.code());
        assertTrue(requests.isEmpty());
    }

    @Test void a_pending_dlt_id_is_never_sent() {
        var msg = new RenderedMessage(Channel.SMS, "sms_order_shipped", 42, "order_shipped", "", "", "", null, null,
                Duration.ofHours(12), false, null, null,
                new SmsCall("PENDING", "PENDING", BODY, List.of("a", "b", "c"), List.of("1", "2", "3")));
        var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(msg, to("919876543210")));
        assertEquals("dlt_mismatch", e.code());
        assertTrue(requests.isEmpty());
    }

    @Test void a_landline_or_missing_phone_is_refused() {
        assertEquals("no_phone", assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("911123456789"))).code());
        assertEquals("no_phone", assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to(null))).code());
        assertTrue(requests.isEmpty());
    }

    @Test void an_error_reply_is_permanent_and_never_suppresses() {
        replies.add(new Reply(200, "{\"type\":\"error\",\"message\":\"Invalid template id\"}"));
        var e = assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("919876543210")));
        assertEquals("msg91_rejected", e.code());
        assertFalse(e.suppress());
        assertEquals(1, requests.size(), "not retried");
    }

    @Test void a_bad_auth_key_is_permanent() {
        replies.add(new Reply(401, "{\"type\":\"error\",\"message\":\"Authentication failure\"}"));
        assertEquals("msg91_auth", assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("919876543210"))).code());
    }

    @Test void a_5xx_is_retried_then_succeeds() throws Exception {
        replies.add(new Reply(503, ""));
        replies.add(new Reply(200, "{\"type\":\"success\",\"message\":\"abc123\"}"));
        assertEquals("abc123", adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("919876543210")).providerId());
        assertEquals(2, requests.size());
    }

    @Test void persistent_5xx_gives_up_after_three_attempts_as_transient() {
        var e = assertThrows(ChannelException.Transient.class,
                () -> adapter.send(sms(List.of("#1042", "Delhivery", "1042")), to("919876543210")));
        assertEquals("msg91_500", e.code());
        assertEquals(Msg91Adapter.ATTEMPTS, requests.size());
    }
}
