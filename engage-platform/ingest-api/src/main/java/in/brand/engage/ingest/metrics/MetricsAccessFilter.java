package in.brand.engage.ingest.metrics;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /prometheus} is served on the public port (nginx forwards every
 * path), so it needs its own lock: {@code Authorization: Bearer METRICS_TOKEN}.
 * Without a configured token the endpoint does not exist (404): fail closed.
 */
@ServerFilter({"/prometheus", "/prometheus/**"})
public class MetricsAccessFilter {

    private final byte[] token;

    public MetricsAccessFilter(@Value("${engage.metrics.token:}") String token) {
        this.token = token == null || token.isBlank() ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> check(HttpRequest<?> request) {
        if (token == null) return HttpResponse.notFound();
        var auth = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (auth == null || !MessageDigest.isEqual(token, auth.getBytes(StandardCharsets.UTF_8))) {
            return HttpResponse.unauthorized();
        }
        return null;   // continue to the endpoint
    }
}
