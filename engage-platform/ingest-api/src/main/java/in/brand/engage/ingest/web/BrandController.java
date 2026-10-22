package in.brand.engage.ingest.web;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The brand's push icons, shipped with the code (resources/brand), so every
 * notification carries the logo without any server setting:
 * {@code push-icon-192.png} (white W on the logo's red, beside the text) and
 * {@code push-badge-96.png} (white W on transparent, Android's status bar).
 * {@code PUSH_ICON_URL} / {@code PUSH_BADGE_URL} override them.
 */
@Controller("/brand")
public class BrandController {

    public static final String ICON = "push-icon-192.png";
    public static final String BADGE = "push-badge-96.png";

    private final Map<String, byte[]> files = Map.of(ICON, read(ICON), BADGE, read(BADGE));

    @Get(value = "/{file}", produces = "*/*")
    public HttpResponse<byte[]> get(@PathVariable String file) {
        var bytes = files.get(file);
        if (bytes == null) return HttpResponse.notFound();
        return HttpResponse.ok(bytes)
                .contentType("image/png")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff");
    }

    private static byte[] read(String name) {
        try (var in = BrandController.class.getResourceAsStream("/brand/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource /brand/" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
