package in.brand.engage.admin.inbox;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.AdminTestData;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.WaInboxLog;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The WhatsApp inbox: threads, the 24 h reply rule, the follow-up template, read/done. Meta is faked. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
@SuppressWarnings("unchecked")
class InboxTest {

    /** Records what would have gone to Meta. */
    static class FakeGraph extends WhatsAppGraph {
        final List<String> sent = new ArrayList<>();
        int n;
        FakeGraph(JsonMapper json) { super(json, "v25.0", "123", "token"); }
        @Override public String sendText(String to, String text) { sent.add("text:" + to + ":" + text); return "wamid.out" + (++n); }
        @Override public String sendTemplate(String to, String name, String language, List<String> params) {
            sent.add("template:" + to + ":" + name + ":" + params);
            return "wamid.out" + (++n);
        }
        @Override public Media media(String mediaId) { return new Media("image/jpeg", new byte[] {1, 2, 3}); }
    }

    @Inject @Client("/") HttpClient client;
    @Inject AdminTestData data;
    @Inject Db db;
    @Inject WhatsAppGraph graph;

    @MockBean(WhatsAppGraph.class)
    WhatsAppGraph fakeGraph(JsonMapper json) {
        return new FakeGraph(json);
    }

    UUID mary;

    @BeforeEach void reset() {
        data.cleanAdminTables();
        data.truncateCustomerTables();
        ((FakeGraph) graph).sent.clear();
        mary = UUID.fromString(data.createIdentity("mary@example.com", "918606572870"));
        db.inTx(c -> {
            Sql.update(c, "DELETE FROM wa_templates WHERE key = 'support_followup_v1'");
            WaInboxLog.inbound(c, mary, "wamid.in1", "image", "Is this in stock?", "media-1", "image/jpeg", null, null,
                    Instant.now().minus(1, ChronoUnit.HOURS));
            return null;
        });
    }

    void window(Instant until) {
        db.inTx(c -> Sql.update(c, "UPDATE profiles SET wa_window_until = ? WHERE identity_id = ?",
                until.atOffset(java.time.ZoneOffset.UTC), mary));
    }

    @Test void the_list_and_thread_show_the_customers_message_and_opening_is_logged() {
        var viewer = data.loginAsViewer(client);
        var unread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/unread").bearerAuth(viewer), Map.class);
        assertEquals(1, ((Number) unread.get("threads")).intValue());
        var denied = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(viewer), Argument.listOf(Map.class)));
        assertEquals(403, denied.getStatus().getCode());

        var analyst = data.loginAsAnalyst(client);
        var list = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(1, list.size());
        assertEquals("Mary", list.getFirst().get("name"));
        assertEquals(1, ((Number) list.getFirst().get("unread")).intValue());
        assertEquals(1, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?q=86065").bearerAuth(analyst), Argument.listOf(Map.class)).size());
        assertEquals(0, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?q=nobody").bearerAuth(analyst), Argument.listOf(Map.class)).size());

        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        var messages = (List<Map<String, Object>>) thread.get("messages");
        assertEquals("Is this in stock?", messages.getFirst().get("body"));
        assertEquals(Boolean.TRUE, messages.getFirst().get("hasMedia"));
        assertEquals("mary@example.com", thread.get("email"));
        assertEquals(1L, db.<Long>inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT count(*) FROM pii_unmask_log WHERE field = 'whatsapp_inbox'"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }));

        client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/read", Map.of()).bearerAuth(analyst));
        unread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/unread").bearerAuth(analyst), Map.class);
        assertEquals(0, ((Number) unread.get("threads")).intValue());

        var media = client.toBlocking().exchange(HttpRequest.GET("/api/inbox/media/" + messages.getFirst().get("id")).bearerAuth(analyst), byte[].class);
        assertEquals("image/jpeg", media.getContentType().orElseThrow().toString());
    }

    @Test void inside_the_window_a_reply_goes_out_and_joins_the_thread() {
        window(Instant.now().plus(23, ChronoUnit.HOURS));
        var analyst = data.loginAsAnalyst(client);
        var sent = client.toBlocking().retrieve(HttpRequest.POST("/api/inbox/" + mary + "/reply", Map.of("text", "Yes, in stock!")).bearerAuth(analyst), Map.class);
        assertEquals("out", sent.get("direction"));
        assertEquals("analyst@example.com", sent.get("sentBy"));
        assertEquals(List.of("text:918606572870:Yes, in stock!"), ((FakeGraph) graph).sent);

        var thread = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox/" + mary).bearerAuth(analyst), Map.class);
        assertEquals(2, ((List<?>) thread.get("messages")).size());
        assertEquals("Yes, in stock!", thread.get("lastPreview"));
        assertEquals(0, ((Number) thread.get("unread")).intValue(), "answering clears the unread count");
    }

    @Test void after_the_window_a_reply_is_refused_and_only_the_approved_follow_up_may_go() {
        window(Instant.now().minus(1, ChronoUnit.MINUTES));
        var analyst = data.loginAsAnalyst(client);
        var closed = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/reply", Map.of("text", "Hello?")).bearerAuth(analyst), Map.class));
        assertEquals(409, closed.getStatus().getCode());

        var pending = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/template", Map.of("templateKey", "support_followup_v1")).bearerAuth(analyst), Map.class));
        assertEquals(409, pending.getStatus().getCode(), "not approved by Meta yet");
        var other = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/inbox/" + mary + "/template", Map.of("templateKey", "campaign_diwali_v1")).bearerAuth(analyst), Map.class));
        assertEquals(400, other.getStatus().getCode(), "marketing templates are not sent from the inbox");

        db.inTx(c -> Sql.update(c, """
                INSERT INTO wa_templates (key, language, provider_name, requested_category, status, quality)
                VALUES ('support_followup_v1', 'en', 'support_followup_v1', 'utility', 'APPROVED', 'GREEN')"""));
        var sent = client.toBlocking().retrieve(HttpRequest.POST("/api/inbox/" + mary + "/template",
                Map.of("templateKey", "support_followup_v1")).bearerAuth(analyst), Map.class);
        assertEquals("support_followup_v1", sent.get("templateKey"));
        assertEquals(List.of("template:918606572870:support_followup_v1:[Mary]"), ((FakeGraph) graph).sent);
        assertEquals(1L, data.countAudit("inbox.template"));
    }

    @Test void done_hides_the_thread_until_the_customer_writes_again() {
        var analyst = data.loginAsAnalyst(client);
        client.toBlocking().exchange(HttpRequest.POST("/api/inbox/" + mary + "/status", Map.of("status", "done")).bearerAuth(analyst));
        assertTrue(client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class)).isEmpty());
        assertEquals(1, client.toBlocking().retrieve(HttpRequest.GET("/api/inbox?status=done").bearerAuth(analyst), Argument.listOf(Map.class)).size());

        db.inTx(c -> { WaInboxLog.inbound(c, mary, "wamid.in2", "text", "Hello again", null, null, null, null, Instant.now()); return null; });
        var open = client.toBlocking().retrieve(HttpRequest.GET("/api/inbox").bearerAuth(analyst), Argument.listOf(Map.class));
        assertEquals(1, open.size());
        assertEquals("Hello again", open.getFirst().get("lastPreview"));
    }
}
