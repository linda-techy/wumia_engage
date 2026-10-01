package in.brand.engage.admin.auth;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Verifies the bearer token and fills {@link CurrentOperator}. Verification is
 * stateless (RS256), plus one cheap check that the session was not revoked and
 * the password has not changed since the token was issued ({@code ver}).
 * A request without a valid token passes through with no operator: the
 * handler's {@code require()} answers 401.
 */
@ServerFilter("/api/**")
public class AuthFilter {

    private final Tokens tokens;
    private final CurrentOperator current;
    private final OperatorRepository operators;
    private final SessionRepository sessions;

    public AuthFilter(Tokens tokens, CurrentOperator current, OperatorRepository operators,
                      SessionRepository sessions) {
        this.tokens = tokens;
        this.current = current;
        this.operators = operators;
        this.sessions = sessions;
    }

    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    public void authenticate(HttpRequest<?> request) {
        var header = request.getHeaders().getAuthorization().orElse(null);
        if (header == null || !header.startsWith("Bearer ")) return;
        try {
            var claims = tokens.verify(header.substring("Bearer ".length()));
            var operator = operators.findById(claims.operatorId()).orElse(null);
            if (operator == null || !operator.active() || operator.ver() != claims.ver()) return;
            if (!sessions.isLive(claims.sessionId())) return;
            current.set(operator.id(), claims.sessionId(), operator.roles());
        } catch (Tokens.InvalidToken ignored) {
            // Leave CurrentOperator empty: the handler's require() answers 401.
        }
    }
}
