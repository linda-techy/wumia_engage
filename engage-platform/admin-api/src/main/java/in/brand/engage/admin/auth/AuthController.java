package in.brand.engage.admin.auth;

import in.brand.engage.admin.auth.OperatorRepository.Operator;
import in.brand.engage.admin.web.Problems;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /api/auth}: login, MFA, refresh, logout, me. The access token goes in
 * the body only (the UI keeps it in memory); the refresh token only in an
 * {@code HttpOnly; Secure; SameSite=Strict} cookie scoped to {@code /api/auth},
 * so no script can read it and no other path ever sends it.
 */
@Controller("/api/auth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthController {

    static final String REFRESH_COOKIE = "engage_refresh";

    @Serdeable
    public record LoginRequest(String email, String password) {}

    @Serdeable
    public record MfaRequest(String mfaToken, String code) {}

    private final AuthService auth;
    private final CurrentOperator current;
    private final OperatorRepository operators;

    public AuthController(AuthService auth, CurrentOperator current, OperatorRepository operators) {
        this.auth = auth;
        this.current = current;
        this.operators = operators;
    }

    @Post("/login")
    public HttpResponse<Map<String, Object>> login(HttpRequest<?> request, @Body LoginRequest body) {
        if (body == null || body.email() == null) throw Problems.badRequest("email and password are required");
        var result = auth.login(body.email(), body.password(), userAgent(request));
        if (result.mfaToken() != null) {
            return HttpResponse.ok(Map.of("mfaRequired", true, "mfaToken", result.mfaToken()));
        }
        return signedIn(result.signed());
    }

    @Post("/mfa")
    public HttpResponse<Map<String, Object>> mfa(HttpRequest<?> request, @Body MfaRequest body) {
        if (body == null) throw Problems.badRequest("mfaToken and code are required");
        return signedIn(auth.mfa(body.mfaToken(), body.code(), userAgent(request)));
    }

    @Post("/refresh")
    public HttpResponse<Map<String, Object>> refresh(HttpRequest<?> request) {
        var signed = auth.refresh(refreshCookie(request), userAgent(request));
        return HttpResponse.ok(Map.<String, Object>of("accessToken", signed.accessToken()))
                .cookie(cookie(signed.refreshToken(), Duration.ofDays(14)));
    }

    @Post("/logout")
    public HttpResponse<?> logout(HttpRequest<?> request) {
        auth.logout(refreshCookie(request));
        return HttpResponse.noContent().cookie(cookie("", Duration.ZERO));
    }

    @Get("/me")
    public Map<String, Object> me() {
        var operator = operators.findById(current.id()).orElseThrow(() -> Problems.unauthorized("not authenticated"));
        var body = operatorView(operator);
        body.put("mfaEnrolled", operator.mfaEnrolled());
        return body;
    }

    private HttpResponse<Map<String, Object>> signedIn(AuthService.Signed signed) {
        var body = new LinkedHashMap<String, Object>();
        body.put("accessToken", signed.accessToken());
        body.put("operator", operatorView(signed.operator()));
        return HttpResponse.ok((Map<String, Object>) body).cookie(cookie(signed.refreshToken(), Duration.ofDays(14)));
    }

    static LinkedHashMap<String, Object> operatorView(Operator o) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", o.id().toString());
        view.put("email", o.email());
        view.put("fullName", o.fullName());
        view.put("roles", o.roles());
        return view;
    }

    private static Cookie cookie(String value, Duration maxAge) {
        return Cookie.of(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite(SameSite.Strict)
                .path("/api/auth")
                .maxAge(maxAge);
    }

    @Nullable
    private static String refreshCookie(HttpRequest<?> request) {
        return request.getCookies().findCookie(REFRESH_COOKIE).map(Cookie::getValue).orElse(null);
    }

    @Nullable
    private static String userAgent(HttpRequest<?> request) {
        var ua = request.getHeaders().get(HttpHeaders.USER_AGENT);
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 300));
    }
}
