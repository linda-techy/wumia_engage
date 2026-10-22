package in.brand.engage.ingest.web;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Product photos for WhatsApp header images ({@code /media/product/<product id>-<hash>}),
 * fetched by Meta when it delivers a photo template.
 *
 * <p>Meta takes only JPEG and PNG. Shopify's CDN picks the format from the
 * fetcher's {@code Accept} header and serves WebP to anyone who accepts it,
 * so the photo is fetched here asking for JPEG or PNG only, and anything else
 * is refused rather than handed to Meta. Only {@code products.image_url} on
 * Shopify's CDN is ever fetched (never a URL from the request), 1024 px wide.
 * The hash in the path is only there so a changed photo gets a new link.
 *
 * <p>Public by design: these are the storefront's own product photos.
 */
@Controller("/media/product")
public class ProductPhotoController {

    private static final Pattern REF = Pattern.compile("(\\d{1,20})(-[0-9a-f]{8})?");

    private final Db db;
    private final Fetcher fetcher;

    public ProductPhotoController(Db db, Fetcher fetcher) {
        this.db = db;
        this.fetcher = fetcher;
    }

    @Get("/{ref}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> get(@PathVariable String ref) {
        var m = REF.matcher(ref);
        if (!m.matches()) return HttpResponse.notFound();
        String url = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT image_url FROM products WHERE product_id = ?", m.group(1));
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
        var photo = url == null ? null : fetcher.get(url);
        if (photo == null) return HttpResponse.notFound();
        return HttpResponse.ok(photo.bytes())
                .contentType(photo.type())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'");
    }

    record Photo(String type, byte[] bytes) {}

    /** Fetches from Shopify's CDN and keeps the last few photos for half an hour. */
    @Singleton
    public static class Fetcher {

        private static final Logger LOG = LoggerFactory.getLogger(Fetcher.class);
        static final Set<String> TYPES = Set.of("image/jpeg", "image/png");
        static final int MAX_BYTES = 5 * 1024 * 1024;      // Meta's limit for a header image
        static final int WIDTH = 1024;
        static final Duration KEEP = Duration.ofMinutes(30);
        static final int ENTRIES = 64;

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
        private final Clock clock;
        private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > ENTRIES;
            }
        };

        private record Cached(Photo photo, Instant at) {}

        public Fetcher() {
            this(Clock.systemUTC());
        }

        Fetcher(Clock clock) {
            this.clock = clock;
        }

        /** @return the photo, or null when it is not on the CDN, not JPEG or PNG, too big or unreachable */
        Photo get(String imageUrl) {
            var url = sized(imageUrl);
            if (url == null) return null;
            synchronized (cache) {
                var hit = cache.get(url);
                if (hit != null && hit.at().plus(KEEP).isAfter(clock.instant())) return hit.photo();
            }
            var photo = fetch(url);
            if (photo != null) {
                synchronized (cache) {
                    cache.put(url, new Cached(photo, clock.instant()));
                }
            }
            return photo;
        }

        /** The CDN URL at {@value #WIDTH} px; null for anything not on cdn.shopify.com over https. */
        static String sized(String imageUrl) {
            if (imageUrl == null) return null;
            URI uri;
            try {
                uri = URI.create(imageUrl.strip());
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (!"https".equals(uri.getScheme()) || !"cdn.shopify.com".equals(uri.getHost()) || uri.getPort() != -1) return null;
            var s = uri.toString();
            if (s.contains("width=")) return s;
            return s + (uri.getRawQuery() == null ? "?" : "&") + "width=" + WIDTH;
        }

        protected Photo fetch(String url) {
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "image/jpeg, image/png")
                    .header("User-Agent", "Mozilla/5.0 (compatible; WUMIKA-Engage/1.0; +https://engage.wumika.com)").GET().build();
            try {
                var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                try (var in = res.body()) {
                    var type = res.headers().firstValue("Content-Type").orElse("").split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
                    if (res.statusCode() != 200 || !TYPES.contains(type)) {
                        LOG.warn("product photo {}: HTTP {} {}, not served", url, res.statusCode(), type);
                        return null;
                    }
                    var bytes = in.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) {
                        LOG.warn("product photo {}: over 5 MB, not served", url);
                        return null;
                    }
                    return new Photo(type, bytes);
                }
            } catch (IOException e) {
                LOG.warn("product photo {}: {}", url, e.toString());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
