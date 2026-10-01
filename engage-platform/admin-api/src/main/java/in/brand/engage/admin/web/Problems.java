package in.brand.engage.admin.web;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/** RFC-9457 problem+json. One shape for every error the console renders. */
public final class Problems {

    private Problems() {}

    public static class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final HttpStatus status;
        private final String type;
        private final transient Map<String, Object> extras;

        public ApiException(HttpStatus status, String type, String detail) {
            this(status, type, detail, Map.of());
        }

        public ApiException(HttpStatus status, String type, String detail, Map<String, Object> extras) {
            super(detail);
            this.status = status;
            this.type = type;
            this.extras = extras;
        }

        public HttpStatus status() {
            return status;
        }

        public String type() {
            return type;
        }

        /** Extension members (RFC 9457 §3.2), e.g. the enrolment token on "MFA enrolment required". */
        public Map<String, Object> extras() {
            return extras;
        }
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", detail);
    }

    public static ApiException forbidden(String type, String detail, Map<String, Object> extras) {
        return new ApiException(HttpStatus.FORBIDDEN, type, detail, extras);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", detail);
    }

    public static ApiException conflict(String type, String detail) {
        return new ApiException(HttpStatus.CONFLICT, type, detail);
    }

    @Singleton
    @Produces(MediaType.APPLICATION_JSON)
    public static class Handler implements ExceptionHandler<ApiException, HttpResponse<Map<String, Object>>> {

        @Override
        public HttpResponse<Map<String, Object>> handle(HttpRequest request, ApiException e) {
            var body = new LinkedHashMap<String, Object>();
            body.put("type", "https://engage.wumika.com/errors/" + e.type());
            body.put("title", e.type().replace('-', ' '));
            body.put("status", e.status().getCode());
            body.put("detail", e.getMessage());
            body.put("instance", request.getPath());
            e.extras().forEach(body::putIfAbsent);
            return HttpResponse.<Map<String, Object>>status(e.status())
                    .contentType("application/problem+json")
                    .body(body);
        }
    }
}
