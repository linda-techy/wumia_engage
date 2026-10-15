package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.UnsubscribeToken;
import in.brand.engage.persistence.ConsentWriter;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.EventWriter;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /u/{token}}: the unsubscribe link in every marketing email (P7-T01).
 *
 * <ul>
 * <li>{@code GET} shows a page with one button. It does not unsubscribe by
 *     itself: mail scanners open links, and must not unsubscribe people.</li>
 * <li>{@code POST} (the button, or Gmail's and Yahoo's one-click,
 *     {@code List-Unsubscribe=One-Click}, RFC 8058) withdraws email marketing
 *     consent at once: no login, no confirmation step, 200. Again is a no-op.</li>
 * </ul>
 * Order and delivery emails come from Shopify and are not affected.
 */
@Controller("/u")
public class UnsubscribeController {

    private final UnsubscribeToken tokens;
    private final ConsentWriter consents;
    private final EventWriter events;
    private final Db db;

    public UnsubscribeController(UnsubscribeToken tokens, ConsentWriter consents, EventWriter events, Db db) {
        this.tokens = tokens;
        this.consents = consents;
        this.events = events;
        this.db = db;
    }

    @Get(value = "/{token}", produces = MediaType.TEXT_HTML)
    public HttpResponse<String> page(@PathVariable String token) {
        if (tokens.parse(token).isEmpty()) return notFound();
        return HttpResponse.ok(page("Unsubscribe from WUMIKA emails",
                "<p>You will stop getting offers and news from WUMIKA by email. Order and delivery emails still arrive.</p>"
                        + "<form method=\"post\"><button type=\"submit\" style=\"padding:12px 24px;font-size:16px;background:#7a2e3a;"
                        + "color:#fff;border:0;border-radius:4px;cursor:pointer;\">Unsubscribe</button></form>"))
                .header("Cache-Control", "no-store");
    }

    @Post(value = "/{token}", consumes = MediaType.ALL, produces = MediaType.TEXT_HTML)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<String> unsubscribe(@PathVariable String token) {
        var identity = tokens.parse(token).orElse(null);
        if (identity == null) return notFound();
        db.inTx(c -> {
            if (!granted(c, identity)) return null;                       // already out: nothing to do
            var now = Instant.now();
            var ref = "email_unsubscribe:" + identity + ":" + now.toEpochMilli();
            consents.statement(c, identity, "withdrawn", "email_unsubscribe", null, ref, now,
                    List.<String[]>of(new String[] {"email", "marketing"}));
            events.write(c, identity, "email_unsubscribed", "storefront", ref, Map.of("via", "link"));
            return null;
        });
        return HttpResponse.ok(page("You are unsubscribed",
                "<p>You will not get offers or news from WUMIKA by email any more. Order and delivery emails still arrive.</p>"))
                .header("Cache-Control", "no-store");
    }

    private static boolean granted(java.sql.Connection c, UUID identity) throws java.sql.SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT 1 FROM consent_current WHERE identity_id = ? AND channel = 'email' AND purpose = 'marketing'
                   AND state = 'granted'""", identity);
             var rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static HttpResponse<String> notFound() {
        return HttpResponse.<String>status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML_TYPE)
                .body(page("Link not valid", "<p>This unsubscribe link is not valid. Use the link in the latest WUMIKA email.</p>"));
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\"><meta name=\"robots\" content=\"noindex\"><title>" + title + "</title></head>"
                + "<body style=\"margin:0;background:#f6f1ee;font-family:Arial,Helvetica,sans-serif;color:#2b2226;\">"
                + "<main style=\"max-width:480px;margin:48px auto;background:#fff;padding:32px;border-radius:6px;\">"
                + "<p style=\"font-family:Georgia,serif;letter-spacing:4px;color:#7a2e3a;margin:0 0 16px;\">WUMIKA</p>"
                + "<h1 style=\"font-size:22px;margin:0 0 12px;\">" + title + "</h1>" + body + "</main></body></html>";
    }
}
