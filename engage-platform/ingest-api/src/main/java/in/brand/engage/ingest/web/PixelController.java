package in.brand.engage.ingest.web;

import in.brand.engage.ingest.config.EngageProperties;
import in.brand.engage.ingest.pixel.PixelEvent;
import in.brand.engage.ingest.pixel.PixelEvents;
import in.brand.engage.ingest.push.RateLimiter;
import in.brand.engage.ingest.push.StorefrontRequests.BadRequest;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /pixel/events}: the web pixel's intake (phase-2 §7, P2-T06).
 *
 * <p>Public by nature: the pixel runs in Shopify's sandboxed iframe (origin
 * {@code null}) and the write key sits in its settings. The key therefore only
 * keeps out casual traffic; the rate limit per {@code clientId} and treating
 * every event as a timing hint (no identities, no consent) are what make it
 * safe. CORS is opened on this route only.
 *
 * <p>Statuses: 204 recorded, 400 invalid, 401 wrong key, 429 rate limited,
 * 503 no {@code PIXEL_WRITE_KEY} configured. Bodies are never logged.
 */
@Controller("/pixel")
public class PixelController {

    private static final Logger LOG = LoggerFactory.getLogger(PixelController.class);
    static final int PER_CLIENT_PER_HOUR = 300;

    private final byte[] writeKey;
    private final PixelEvents events;
    private final RateLimiter limiter;

    public PixelController(EngageProperties.Pixel pixel, PixelEvents events, RateLimiter limiter) {
        var key = pixel.writeKey();
        this.writeKey = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
        this.events = events;
        this.limiter = limiter;
        if (writeKey == null) LOG.warn("PIXEL_WRITE_KEY is not set: /pixel/events answers 503");
    }

    @Post("/events")
    @CrossOrigin(allowedOriginsRegex = ".*", allowedMethods = HttpMethod.POST,
            allowedHeaders = {"Content-Type", "X-Engage-Key"}, allowCredentials = false, maxAge = 86400)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> event(@Nullable @Header("X-Engage-Key") String key, @Body PixelEvent body) {
        if (writeKey == null) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (key == null || !MessageDigest.isEqual(writeKey, key.getBytes(StandardCharsets.UTF_8))) {
            return HttpResponse.unauthorized();
        }
        var e = body.validate();
        if (!limiter.allow("pixel", e.clientId(), PER_CLIENT_PER_HOUR, Duration.ofHours(1))) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS);
        }
        events.record(e);
        return HttpResponse.noContent();
    }

    @Error(exception = BadRequest.class)
    public HttpResponse<?> badRequest(BadRequest e) {
        return HttpResponse.badRequest(Map.of("error", e.getMessage()));
    }
}
