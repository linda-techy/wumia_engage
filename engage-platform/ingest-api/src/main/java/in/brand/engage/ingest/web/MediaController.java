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
import java.util.regex.Pattern;

/**
 * Campaign posters (V38 {@code media}), fetched by the browser when it draws a
 * push notification. Public by design: they are marketing images. The name is
 * the SHA-256 of the bytes, so a URL never changes meaning and can be cached
 * for a year. {@code nosniff}, and only the two image types the upload allows.
 */
@Controller("/media")
public class MediaController {

    private static final Pattern FILE = Pattern.compile("([0-9a-f]{64})\\.(jpg|png)");

    private final Db db;

    public MediaController(Db db) {
        this.db = db;
    }

    @Get("/{file}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> get(@PathVariable String file) {
        var m = FILE.matcher(file);
        if (!m.matches()) return HttpResponse.notFound();
        var type = m.group(2).equals("png") ? "image/png" : "image/jpeg";
        byte[] bytes = db.inTx(c -> {
            try (var ps = Sql.prepare(c, "SELECT bytes FROM media WHERE id = ? AND content_type = ?", m.group(1), type);
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        });
        if (bytes == null) return HttpResponse.notFound();
        return HttpResponse.ok(bytes)
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'");
    }
}
