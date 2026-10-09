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
    private final LoginRateLimit loginLimit;

    public AuthController(AuthService auth, CurrentOperator current, OperatorRepository operators,
                          LoginRateLimit loginLimit) {
        this.auth = auth;
        this.current = current;
        this.operators = operators;
        this.loginLimit = loginLimit;
    }

    @PublicEndpoint("checks email + password; per-account lock and per-IP limit")
    @Post("/login")
    public HttpResponse<Map<String, Object>> login(HttpRequest<?> request, @Body LoginRequest body) {
        if (body == null || body.email() == null) throw Problems.badRequest("email and password are required");
        if (!loginLimit.allow(clientIp(request))) {
            throw new Problems.ApiException(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts",
                    "Too many sign-in attempts from this network. Wait a few minutes.");
        }
        var result = auth.login(body.email(), body.password(), userAgent(request));
        if (result.mfaToken() != null) {
            return HttpResponse.ok(Map.of("mfaRequired", true, "mfaToken", result.mfaToken()));
        }
        return signedIn(result.signed());
    }

    @PublicEndpoint("checks the 5-minute MFA token from login and a single-use TOTP or recovery code")
    @Post("/mfa")
    public HttpResponse<Map<String, Object>> mfa(HttpRequest<?> request, @Body MfaRequest body) {
        if (body == null) throw Problems.badRequest("mfaToken and code are required");
        return signedIn(auth.mfa(body.mfaToken(), body.code(), userAgent(request)));
    }

    @PublicEndpoint("checks the HttpOnly refresh cookie; reuse revokes the family")
    @Post("/refresh")
    public HttpResponse<Map<String, Object>> refresh(HttpRequest<?> request) {
        var signed = auth.refresh(refreshCookie(request), userAgent(request));
        return HttpResponse.ok(Map.<String, Object>of("accessToken", signed.accessToken()))
                .cookie(cookie(signed.refreshToken(), Duration.ofDays(14)));
    }

    @PublicEndpoint("revokes whatever family the refresh cookie names; harmless without one")
    @Post("/logout")
    public HttpResponse<?> logout(HttpRequest<?> request) {
        auth.logout(refreshCookie(request));
        return HttpResponse.noContent().cookie(cookie("", Duration.ZERO));
    }

    @Serdeable
    public record EnrolRequest(@Nullable String enrolToken) {}

    @Serdeable
    public record ConfirmRequest(String code, @Nullable String enrolToken) {}

    @Serdeable
    public record SetPasswordRequest(String token, String password) {}

    /** Signed in (bearer), or with the enrolment-only token from login's 403. */
    @PublicEndpoint("needs a session or the enrolment-only token from login's 403")
    @Post("/mfa/enrol")
    public Map<String, Object> enrol(@Nullable @Body EnrolRequest body) {
        var enrolment = auth.startEnrolment(enrolee(body == null ? null : body.enrolToken()));
        return Map.of("provisioningUri", enrolment.provisioningUri(), "secret", enrolment.secret());
    }

    @PublicEndpoint("needs a session or the enrolment-only token from login's 403")
    @Post("/mfa/confirm")
    public Map<String, Object> confirm(@Body ConfirmRequest body) {
        if (body == null || body.code() == null) throw Problems.badRequest("code is required");
        return Map.of("recoveryCodes", auth.confirmEnrolment(enrolee(body.enrolToken()), body.code()));
    }

    @Serdeable
    public record CodeRequest(@Nullable String code) {}

    /** New recovery codes for the signed-in operator; the old ones stop working. Needs a current TOTP code. */
    @RequiresRole(Role.VIEWER)
    @Post("/mfa/recovery-codes")
    public Map<String, Object> recoveryCodes(@Body CodeRequest body) {
        if (body == null || body.code() == null) throw Problems.badRequest("a current authenticator code is required");
        return Map.of("recoveryCodes", auth.reissueRecoveryCodes(current.id(), body.code()));
    }

    @PublicEndpoint("checks the one-time set-password link token, bound to the operator's ver")
    @Post("/set-password")
    public HttpResponse<?> setPassword(@Body SetPasswordRequest body) {
        if (body == null || body.token() == null) throw Problems.badRequest("token and password are required");
        auth.setPassword(body.token(), body.password());
        return HttpResponse.noContent();
    }

    private java.util.UUID enrolee(@Nullable String enrolToken) {
        return enrolToken != null && !enrolToken.isBlank() ? auth.enrolee(enrolToken) : current.id();
    }

    @RequiresRole(Role.VIEWER)
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

    /** nginx sets X-Real-IP to the peer it saw; without nginx (local, tests) the socket's address. */
    static String clientIp(HttpRequest<?> request) {
        return clientIp(request.getRemoteAddress().getAddress(), request.getHeaders().get("X-Real-IP"));
    }

    /**
     * X-Real-IP only from our own proxy: a loopback or private peer (nginx on
     * the host, the Docker bridge). From anyone else the header is forgeable,
     * and honouring it would give every request a fresh limit bucket.
     */
    static String clientIp(java.net.InetAddress peer, @Nullable String realIp) {
        boolean viaProxy = peer.isLoopbackAddress() || peer.isSiteLocalAddress();
        if (viaProxy && realIp != null && !realIp.isBlank()) return realIp.strip();
        return peer.getHostAddress();
    }

    @Nullable
    private static String userAgent(HttpRequest<?> request) {
        var ua = request.getHeaders().get(HttpHeaders.USER_AGENT);
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 300));
    }
}
