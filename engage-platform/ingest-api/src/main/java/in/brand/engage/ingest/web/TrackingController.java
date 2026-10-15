package in.brand.engage.ingest.web;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.core.shopify.AppProxyVerifier;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
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
 * {@code GET /shopify/proxy/t/{ref}}, i.e. {@code https://<store>/apps/push/t/<ref>}:
 * the "Track" link in order updates (P4-T08). Signed by Shopify's app proxy
 * like every storefront route; the ref is signed by us ({@link TrackRef}).
 * Redirects to the courier's tracking page; until the parcel has one, a short
 * page in the store's theme says so.
 */
@Controller("/shopify/proxy/t")
public class TrackingController {

    private static final Logger LOG = LoggerFactory.getLogger(TrackingController.class);
    static final MediaType LIQUID = new MediaType("application/liquid");

    private final AppProxyVerifier verifier;
    private final TrackRef refs;
    private final Db db;

    public TrackingController(AppProxyVerifier verifier, TrackRef refs, Db db) {
        this.verifier = verifier;
        this.refs = refs;
        this.db = db;
    }

    @Get("/{ref}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> track(HttpRequest<?> request, @PathVariable String ref) {
        try {
            verifier.verify(request.getParameters().asMap(), Instant.now());
        } catch (AppProxyVerifier.ProxySignatureException e) {
            LOG.warn("rejected tracking request: {}", e.getMessage());
            return HttpResponse.unauthorized();
        }
        var shipment = refs.parse(ref);
        if (shipment.isEmpty()) return page(HttpStatus.NOT_FOUND, "This tracking link is not valid.");
        var url = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT tracking_url FROM shipments WHERE id = ?", shipment.getAsLong());
                 var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
            }
        });
        if (url.isPresent() && (url.get().startsWith("https://") || url.get().startsWith("http://"))) {
            try {
                return HttpResponse.redirect(URI.create(url.get()));
            } catch (IllegalArgumentException e) {
                LOG.warn("shipment {} has an unusable tracking URL", shipment.getAsLong());
            }
        }
        return page(HttpStatus.OK, "Your parcel's tracking page appears here once the courier has scanned it. Please check again later.");
    }

    private static HttpResponse<String> page(HttpStatus status, String text) {
        return HttpResponse.<String>status(status).contentType(LIQUID)
                .body("<div class=\"page-width\" style=\"padding:3rem 0\"><p>" + text + "</p></div>");
    }
}
