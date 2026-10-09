package in.brand.engage.channels.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.WaTemplateCall;
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

/** P4-T02 against a fake Graph API: one test per row of the error table. */
class WhatsAppCloudAdapterTest {

    record Reply(int status, String body) {}

    HttpServer server;
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    WhatsAppCloudAdapter adapter;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v23.0/PHONE_ID/messages", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            var r = replies.isEmpty() ? new Reply(500, "") : replies.poll();
            var bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        adapter = new WhatsAppCloudAdapter(ObjectMapper.getDefault(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "v23.0", "PHONE_ID", "test-token", 0);
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    static RenderedMessage template() {
        return new RenderedMessage(Channel.WHATSAPP, "order_confirmed_v1", 42, "order_tracking", "", "", "https://w.example/o/1",
                null, null, Duration.ofHours(12), false, null,
                new WaTemplateCall("order_confirmed_v1", "en", List.of("Priya", "#1042"), List.of("o/1042")));
    }

    static Addresses phone() {
        return new Addresses(List.of(), "919876543210", null);
    }

    static String error(int code) {
        return "{\"error\":{\"message\":\"m" + code + "\",\"type\":\"OAuthException\",\"code\":" + code + "}}";
    }

    @Test void an_accepted_template_returns_the_wamid_and_sends_the_approved_name_language_and_parameters() throws Exception {
        replies.add(new Reply(200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"wa_id\":\"919876543210\"}],"
                + "\"messages\":[{\"id\":\"wamid.ABC\"}]}"));

        var result = adapter.send(template(), phone());

        assertEquals("wamid.ABC", result.providerId());
        assertEquals("Bearer test-token", auth.getFirst());
        var sent = requests.getFirst();
        assertTrue(sent.contains("\"to\":\"919876543210\""), sent);
        assertTrue(sent.contains("\"name\":\"order_confirmed_v1\""), sent);
        assertTrue(sent.contains("\"code\":\"en\""), sent);
        int first = sent.indexOf("\"text\":\"Priya\""), second = sent.indexOf("\"text\":\"#1042\"");
        assertTrue(first > 0 && second > first, "body parameters in placeholder order: " + sent);
        assertTrue(sent.contains("\"sub_type\":\"url\"") && sent.contains("\"index\":\"0\"")
                && sent.contains("\"text\":\"o/1042\""), sent);
    }

    @Test void a_message_without_a_template_is_a_free_form_service_reply() throws Exception {
        replies.add(new Reply(200, "{\"messages\":[{\"id\":\"wamid.TXT\"}]}"));
        var reply = new RenderedMessage(Channel.WHATSAPP, "wa_stop_confirm", 7, "stop", "", "You will not get messages from us.",
                "https://w.example", null, null, Duration.ofHours(1), false, null, null);
        adapter.send(reply, phone());
        assertTrue(requests.getFirst().contains("\"type\":\"text\""));
        assertTrue(requests.getFirst().contains("You will not get messages from us."));
    }

    @Test void undeliverable_131026_escalates_at_once_never_suppresses_and_is_not_retried() {
        replies.add(new Reply(400, error(131026)));
        var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(template(), phone()));
        assertEquals("wa_131026", e.code());
        assertTrue(e.escalateImmediately());
        assertFalse(e.suppress(), "a capability signal, not a dead customer (CLAUDE.md §4.1)");
        assertEquals(1, requests.size());
    }

    @Test void the_marketing_limit_131049_escalates_too() {
        replies.add(new Reply(400, error(131049)));
        var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(template(), phone()));
        assertTrue(e.escalateImmediately());
        assertFalse(e.suppress());
    }

    @Test void window_stop_and_template_errors_are_permanent_without_escalation_or_retry() {
        for (int code : new int[] {131047, 131050, 132001, 100}) {
            requests.clear();
            replies.add(new Reply(400, error(code)));
            var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(template(), phone()), "code " + code);
            assertEquals("wa_" + code, e.code());
            assertFalse(e.escalateImmediately(), "code " + code);
            assertFalse(e.suppress(), "code " + code);
            assertEquals(1, requests.size(), "no retry for " + code);
        }
    }

    @Test void rate_limits_and_5xx_are_retried_three_times_then_reported_transient() {
        replies.add(new Reply(429, error(130429)));
        replies.add(new Reply(400, error(131056)));
        replies.add(new Reply(503, ""));
        var e = assertThrows(ChannelException.Transient.class, () -> adapter.send(template(), phone()));
        assertEquals("wa_http_503", e.code());
        assertEquals(3, requests.size());
    }

    @Test void a_transient_failure_followed_by_success_sends_once() throws Exception {
        replies.add(new Reply(429, error(130429)));
        replies.add(new Reply(200, "{\"messages\":[{\"id\":\"wamid.RETRY\"}]}"));
        assertEquals("wamid.RETRY", adapter.send(template(), phone()).providerId());
        assertEquals(2, requests.size());
    }

    @Test void no_phone_fails_without_calling_meta() {
        var e = assertThrows(ChannelException.Permanent.class,
                () -> adapter.send(template(), new Addresses(List.of(), null, "a@example.com")));
        assertEquals("no_phone", e.code());
        assertEquals(0, requests.size());
    }
}
