package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.core.shopify.AppProxyVerifier;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /shopify/proxy/pay/{ref}}, i.e. {@code https://<store>/apps/push/pay/<ref>}:
 * the short link in the payment-failed SMS (P5-T02), where Shopify's checkout
 * recovery URL is far over the 30-character DLT variable limit. Signed by
 * Shopify's app proxy; the ref is a signed payment-failed run
 * ({@link TrackRef#ofPayment}). Redirects, on the store's own domain, to the
 * saved checkout the run carries, or to the cart.
 */
@Controller("/shopify/proxy/pay")
public class PayLinkController {

    private static final Logger LOG = LoggerFactory.getLogger(PayLinkController.class);

    private final AppProxyVerifier verifier;
    private final TrackRef refs;
    private final Db db;

    public PayLinkController(AppProxyVerifier verifier, TrackRef refs, Db db) {
        this.verifier = verifier;
        this.refs = refs;
        this.db = db;
    }

    @Get("/{ref}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> pay(HttpRequest<?> request, @PathVariable String ref) {
        try {
            verifier.verify(request.getParameters().asMap(), Instant.now());
        } catch (AppProxyVerifier.ProxySignatureException e) {
            LOG.warn("rejected pay link request: {}", e.getMessage());
            return HttpResponse.unauthorized();
        }
        var run = refs.parsePayment(ref);
        if (run.isEmpty()) {
            return HttpResponse.<String>status(HttpStatus.NOT_FOUND).contentType(TrackingController.LIQUID)
                    .body("<div class=\"page-width\" style=\"padding:3rem 0\"><p>This payment link is not valid.</p></div>");
        }
        var path = db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT vars->>'pay_path' FROM cascade_runs WHERE id = ? AND intent_key = 'payment_failed'""",
                    run.getAsLong());
                 var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
            }
        }).filter(p -> p.matches("[A-Za-z0-9/_.~%?=&-]{1,500}") && !p.startsWith("/")).orElse("cart");
        // Relative: the proxy serves this on the store's domain, so the checkout opens there.
        return HttpResponse.redirect(URI.create("/" + path));
    }
}
