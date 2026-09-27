package in.brand.engage.ingest.web;

import in.brand.engage.core.shopify.AppProxyVerifier;
import in.brand.engage.core.shopify.AppProxyVerifier.ProxyContext;
import in.brand.engage.core.shopify.AppProxyVerifier.ProxySignatureException;
import in.brand.engage.ingest.push.RateLimiter;
import in.brand.engage.ingest.push.StorefrontRequests.BadRequest;
import in.brand.engage.ingest.push.StorefrontRequests.Cart;
import in.brand.engage.ingest.push.StorefrontRequests.Engagement;
import in.brand.engage.ingest.push.StorefrontRequests.NotifyMe;
import in.brand.engage.ingest.push.StorefrontRequests.PromptEvent;
import in.brand.engage.ingest.push.StorefrontRequests.Refresh;
import in.brand.engage.ingest.push.StorefrontRequests.Register;
import in.brand.engage.ingest.push.StorefrontRequests.Unregister;
import in.brand.engage.ingest.push.SubscriberService;
import in.brand.engage.ingest.push.SubscriberService.Outcome;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storefront endpoints behind the Shopify App Proxy: the storefront's
 * {@code /apps/push/*} arrives here as {@code /shopify/proxy/*}
 * (shopify.app.dev.toml [app_proxy]). See phase-2-shopify-web-push.md §8.
 *
 * <p>Every POST follows the same shape: verify the proxy signature → validate
 * the body → rate-limit → act. Only the signed query parameters are trusted;
 * the body never names a customer.
 *
 * <p>Status codes the storefront sees: 200 stored, 202 acknowledged but not
 * stored (customer allowlist), 204 recorded, 400 invalid body, 401 unsigned or
 * stale, 422 unregistered consent copy, 429 rate limited.
 */
@Controller("/shopify/proxy")
public class StorefrontProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(StorefrontProxyController.class);

    private final AppProxyVerifier verifier;
    private final SubscriberService subscribers;
    private final RateLimiter limiter;
    private final String serviceWorker;
    private final String serviceWorkerVersion;

    public StorefrontProxyController(AppProxyVerifier verifier, SubscriberService subscribers, RateLimiter limiter) {
        this.verifier = verifier;
        this.subscribers = subscribers;
        this.limiter = limiter;
        var raw = resource("/push/sw.js");
        // Content hash, so a deploy that changes the worker changes the version
        // the worker reports in its beacons.
        this.serviceWorkerVersion = sha256Hex(raw).substring(0, 12);
        this.serviceWorker = raw.replace("__SW_VERSION__", serviceWorkerVersion);
    }

    /**
     * Unsigned on purpose: it is a public script, and browsers fetch it again on
     * every update check. {@code no-cache}, not {@code no-store}: the browser
     * must revalidate, but may keep the file for offline wake-ups.
     */
    @Get(value = "/sw.js", produces = "application/javascript")
    public HttpResponse<String> serviceWorker() {
        return HttpResponse.ok(serviceWorker)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Engage-SW", serviceWorkerVersion);
    }

    @Post("/register")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> register(HttpRequest<?> request, @Body Register body) {
        var ctx = verify(request);
        var r = body.validate();
        limit("register", r.anonId(), 10, Duration.ofHours(1));
        var result = subscribers.register(ctx, r, origin(request, ctx), request.getHeaders().get(HttpHeaders.USER_AGENT));
        return switch (result.outcome()) {
            case STORED -> HttpResponse.ok(Map.of("deviceId", result.deviceId()));
            case FILTERED -> HttpResponse.accepted();
            case UNKNOWN_COPY -> HttpResponse.unprocessableEntity();
        };
    }

    @Post("/refresh")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> refresh(HttpRequest<?> request, @Body Refresh body) {
        var ctx = verify(request);
        var r = body.validate();
        limit("refresh", r.anonId(), 10, Duration.ofHours(1));
        return status(subscribers.refresh(ctx, r));
    }

    @Post("/unregister")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> unregister(HttpRequest<?> request, @Body Unregister body) {
        var ctx = verify(request);
        var r = body.validate();
        limit("unregister", r.anonId(), 10, Duration.ofHours(1));
        return status(subscribers.unregister(ctx, r));
    }

    @Post("/prompt-event")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> promptEvent(HttpRequest<?> request, @Body PromptEvent body) {
        verify(request);
        var e = body.validate();
        limit("prompt", e.anonId(), 120, Duration.ofHours(1));
        subscribers.promptEvent(e);
        return HttpResponse.noContent();
    }

    @Post("/cart")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> cart(HttpRequest<?> request, @Body Cart body) {
        var ctx = verify(request);
        var r = body.validate();
        limit("cart", r.anonId(), 60, Duration.ofHours(1));
        return status(subscribers.cart(ctx, r));
    }

    @Post("/notify-me")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> notifyMe(HttpRequest<?> request, @Body NotifyMe body) {
        var ctx = verify(request);
        var r = body.validate();
        limit("notify", r.anonId(), 30, Duration.ofHours(1));
        return status(subscribers.notifyMe(ctx, r));
    }

    /**
     * Impression/click beacons from the service worker. Accepted and checked
     * now; they join to {@code sends} once Phase 3 sends carry a send id.
     */
    @Post("/engagement")
    public HttpResponse<?> engagement(HttpRequest<?> request, @Body Engagement body) {
        verify(request);
        body.validate();
        return HttpResponse.noContent();
    }

    /* ------------------------------- errors ------------------------------ */

    @Error(exception = ProxySignatureException.class)
    public HttpResponse<?> unsigned(HttpRequest<?> request, ProxySignatureException e) {
        LOG.warn("rejected storefront request {}: {}", request.getPath(), e.getMessage());
        return HttpResponse.unauthorized();
    }

    @Error(exception = BadRequest.class)
    public HttpResponse<?> badRequest(BadRequest e) {
        return HttpResponse.badRequest(Map.of("error", e.getMessage()));
    }

    @Error(exception = RateLimiter.Limited.class)
    public HttpResponse<?> limited() {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
    }

    /* ------------------------------- helpers ----------------------------- */

    private ProxyContext verify(HttpRequest<?> request) {
        return verifier.verify(request.getParameters().asMap(), Instant.now());
    }

    private void limit(String bucket, String key, int max, Duration per) {
        if (!limiter.allow(bucket, key, max, per)) throw new RateLimiter.Limited();
    }

    private static HttpResponse<?> status(Outcome outcome) {
        return outcome == Outcome.STORED ? HttpResponse.ok() : HttpResponse.accepted();
    }

    /** The page's origin as the browser sent it, else the shop's own domain. */
    private static String origin(HttpRequest<?> request, ProxyContext ctx) {
        var o = request.getHeaders().get(HttpHeaders.ORIGIN);
        return o != null && o.startsWith("https://") && o.length() <= 200 ? o : "https://" + ctx.shop();
    }

    private static String resource(String path) {
        try (var in = StorefrontProxyController.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
