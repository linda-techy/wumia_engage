package in.brand.engage.channels.email;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.EmailContent;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P7-T01 against a fake SES v2 endpoint. */
class SesAdapterTest {

    record Reply(int status, String body, String errorType) {}

    HttpServer server;
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    SesAdapter adapter;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/email/outbound-emails", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            var r = replies.isEmpty() ? new Reply(500, "", null) : replies.poll();
            if (r.errorType() != null) ex.getResponseHeaders().add("x-amzn-ErrorType", r.errorType());
            var bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        adapter = new SesAdapter(ObjectMapper.getDefault(), "ap-south-1", "http://127.0.0.1:" + server.getAddress().getPort(),
                "WUMIKA <hello@mail.wumika.com>", "engage", "unsubscribe@mail.wumika.com", "AKIDTEST", "secret", null, 0,
                Clock.systemUTC());
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    static RenderedMessage email(boolean marketing) {
        return new RenderedMessage(Channel.EMAIL, "email_cart_v1", 42, "cart_recovery", "Still thinking about it?", "text",
                "https://wumika.com/cart", null, null, Duration.ofHours(24), false, null, null, null,
                new EmailContent("Still thinking about it?", "<html>hi</html>", "hi", "https://engage.wumika.com/u/TOKEN", marketing));
    }

    static Addresses to(String email) {
        return new Addresses(List.of(), null, email);
    }

    @Test void a_send_is_signed_carries_one_click_unsubscribe_and_keeps_the_message_id() throws Exception {
        replies.add(new Reply(200, "{\"MessageId\":\"0109019abc-ses\"}", null));
        var result = adapter.send(email(true), to("priya@example.com"));

        assertEquals("0109019abc-ses", result.providerId());
        assertTrue(auth.getFirst().startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/"), auth.getFirst());
        assertTrue(auth.getFirst().contains("/ap-south-1/ses/aws4_request"), auth.getFirst());
        var body = bodies.getFirst();
        assertTrue(body.contains("\"List-Unsubscribe\""), body);
        assertTrue(body.contains("<https://engage.wumika.com/u/TOKEN>, <mailto:unsubscribe@mail.wumika.com?subject=unsubscribe>"), body);
        assertTrue(body.contains("List-Unsubscribe=One-Click"), body);
        assertTrue(body.contains("\"ConfigurationSetName\":\"engage\""), body);
        assertTrue(body.contains("\"ToAddresses\":[\"priya@example.com\"]"), body);
    }

    @Test void a_non_marketing_email_is_refused_without_a_call() {
        var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(email(false), to("priya@example.com")));
        assertEquals("email_not_marketing", e.code());
        assertTrue(bodies.isEmpty());
    }

    @Test void no_address_is_refused() {
        assertEquals("no_email", assertThrows(ChannelException.Permanent.class, () -> adapter.send(email(true), to(null))).code());
    }

    @Test void a_rejection_is_permanent_and_never_suppresses() {
        replies.add(new Reply(400, "{\"message\":\"Email address is not verified.\"}", "MessageRejected:http://internal"));
        var e = assertThrows(ChannelException.Permanent.class, () -> adapter.send(email(true), to("priya@example.com")));
        assertEquals("ses_messagerejected", e.code());
        assertFalse(e.suppress());
        assertEquals(1, bodies.size());
    }

    @Test void throttling_is_retried() throws Exception {
        replies.add(new Reply(429, "{\"message\":\"Maximum sending rate exceeded.\"}", "TooManyRequestsException"));
        replies.add(new Reply(200, "{\"MessageId\":\"m2\"}", null));
        assertEquals("m2", adapter.send(email(true), to("priya@example.com")).providerId());
        assertEquals(2, bodies.size());
    }
}
