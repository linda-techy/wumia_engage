package in.brand.engage.ingest.web;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anything no other handler claimed (P7-T06). Micronaut's default 500 puts the
 * exception's message in the body, and a database error's message can carry
 * customer data. The cause goes to the log under a short reference; the
 * response, seen by storefronts and providers, carries only the reference.
 * Controllers' own {@code @Error} methods and Micronaut's specific handlers
 * still win. A provider that gets this 500 retries, which is what we want.
 */
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public class UnexpectedErrorHandler implements ExceptionHandler<Exception, HttpResponse<Map<String, String>>> {

    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedErrorHandler.class);

    @Override
    public HttpResponse<Map<String, String>> handle(HttpRequest request, Exception e) {
        var ref = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        LOG.error("unexpected error {} on {} {}", ref, request.getMethodName(), request.getPath(), e);
        return HttpResponse.<Map<String, String>>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal", "ref", ref));
    }
}
