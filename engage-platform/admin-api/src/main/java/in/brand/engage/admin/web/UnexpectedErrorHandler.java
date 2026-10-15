package in.brand.engage.admin.web;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anything no other handler claimed (P7-T06). Micronaut's default 500 puts the
 * exception's message in the body, and a database error's message can carry
 * customer data ("Key (email)=(…) already exists"). The cause goes to the log
 * under a short reference; the response carries only the reference.
 * Micronaut's own handlers (bad JSON, conversion, HTTP status) are more
 * specific and still win.
 */
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public class UnexpectedErrorHandler implements ExceptionHandler<Exception, HttpResponse<Map<String, Object>>> {

    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedErrorHandler.class);

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, Exception e) {
        var ref = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        LOG.error("unexpected error {} on {} {}", ref, request.getMethodName(), request.getPath(), e);
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "https://engage.wumika.com/errors/internal");
        body.put("title", "internal error");
        body.put("status", 500);
        body.put("detail", "Something went wrong on our side. Reference " + ref + ".");
        body.put("instance", request.getPath());
        return HttpResponse.<Map<String, Object>>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType("application/problem+json")
                .body(body);
    }
}
