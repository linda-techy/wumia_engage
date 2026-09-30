package in.brand.engage.ingest.web;

import in.brand.engage.core.shopify.SessionTokenVerifier;
import in.brand.engage.core.shopify.SessionTokenVerifier.InvalidToken;
import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.push.RateLimiter;
import in.brand.engage.ingest.push.StorefrontRequests.BadRequest;
import in.brand.engage.ingest.push.ThankYouOptIns;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.server.cors.CrossOrigin;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /shopify/thankyou/whatsapp-optin}: the Thank you page block's
 * "Yes, send updates on WhatsApp" (phase-2 §6.2, P2-T05).
 *
 * <p>Not behind the App Proxy: checkout UI extensions call it directly from
 * {@code https://extensions.shopifycdn.com}, with a Shopify session token
 * (HS256, app secret) as the bearer. The body carries only the order id and
 * the copy version; the phone is read from the order, never from the request.
 *
 * <p>Statuses: 200 consent recorded, 202 stored until the order arrives,
 * 400 invalid body, 401 bad token, 422 unregistered copy, 429 rate limited,
 * 503 client id not configured.
 */
@Controller("/shopify/thankyou")
public class ThankYouController {

    private static final Logger LOG = LoggerFactory.getLogger(ThankYouController.class);
    private static final Pattern ORDER_ID = Pattern.compile("(?:gid://shopify/[A-Za-z]+/)?([0-9]{1,20})");

    private final SessionTokenVerifier tokens;
    private final ThankYouOptIns optIns;
    private final RateLimiter limiter;

    public ThankYouController(EngageProperties.Shopify shopify, ThankYouOptIns optIns, RateLimiter limiter) {
        var clientId = shopify.clientId();
        this.tokens = clientId == null || clientId.isBlank() ? null
                : new SessionTokenVerifier(shopify.apiSecret(), clientId, shopify.shopDomain(), Clock.systemUTC());
        this.optIns = optIns;
        this.limiter = limiter;
        if (tokens == null) LOG.warn("SHOPIFY_CLIENT_ID is not set: /shopify/thankyou answers 503");
    }

    @Serdeable
    public record OptIn(String orderId, String copyVersion) {}

    @Post("/whatsapp-optin")
    @CrossOrigin(allowedOrigins = "https://extensions.shopifycdn.com", allowedMethods = HttpMethod.POST,
            allowedHeaders = {"Authorization", "Content-Type"}, allowCredentials = false, maxAge = 86400)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> optIn(@Nullable @Header("Authorization") String auth, @Body OptIn body) {
        if (tokens == null) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        SessionTokenVerifier.Claims claims;
        try {
            claims = tokens.verify(auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null);
        } catch (InvalidToken e) {
            LOG.warn("rejected Thank you opt-in: {}", e.getMessage());
            return HttpResponse.unauthorized();
        }
        var m = body.orderId() == null ? null : ORDER_ID.matcher(body.orderId().strip());
        if (m == null || !m.matches()) throw new BadRequest("orderId must be an order GID or number");
        var orderId = m.group(1);
        var copy = body.copyVersion();
        if (copy == null || copy.isBlank() || copy.length() > 64) throw new BadRequest("copyVersion is required");
        if (!limiter.allow("thankyou", orderId, 10, Duration.ofHours(1))) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        }

        var outcome = optIns.record(orderId, claims.shop(), copy.strip());
        LOG.info("thank you WhatsApp opt-in for order {} -> {}", orderId, outcome);
        return switch (outcome) {
            case APPLIED -> HttpResponse.ok();
            case PENDING -> HttpResponse.accepted();
            case UNKNOWN_COPY -> HttpResponse.unprocessableEntity();
        };
    }

    @Error(exception = BadRequest.class)
    public HttpResponse<?> badRequest(BadRequest e) {
        return HttpResponse.badRequest(Map.of("error", e.getMessage()));
    }
}
