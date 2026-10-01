package in.brand.engage.admin.auth;

import in.brand.engage.admin.audit.AuditLog;
import in.brand.engage.admin.auth.OperatorRepository.Operator;
import in.brand.engage.admin.config.AdminProperties;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.core.crypto.SecretBox;
import in.brand.engage.core.crypto.Totp;
import in.brand.engage.persistence.Db;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login, the MFA step, refresh and logout ({@code docs/03-auth-and-rbac.md}).
 *
 * <ul>
 * <li>An unknown email and a wrong password are indistinguishable: both run a
 *     password check (a dummy hash for the unknown email) and both answer 401.</li>
 * <li>Five failures lock the account for 15 minutes; a locked account answers
 *     401 even to the right password.</li>
 * <li>No role above ANALYST without MFA enrolled (403); an enrolled operator
 *     gets a 5-minute MFA token instead of a session.</li>
 * <li>A TOTP code is valid once: the step it belongs to is remembered.</li>
 * <li>A login's session and its audit row commit in one transaction.</li>
 * </ul>
 */
@Singleton
public class AuthService {

    /** Roles that may never sign in on a password alone. */
    static final Set<String> ABOVE_ANALYST = Set.of("CAMPAIGN_EDIT", "CAMPAIGN_SEND", "CONFIG_ADMIN", "OWNER");
    static final Duration MFA_TOKEN_TTL = Duration.ofMinutes(5);

    /** A signed-in operator: the access token for the body, the refresh token for the cookie. */
    public record Signed(String accessToken, Operator operator, String refreshToken) {}

    /** Either an MFA challenge ({@code mfaToken}) or a session ({@code signed}). */
    public record LoginResult(String mfaToken, Signed signed) {}

    private final OperatorRepository operators;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final Tokens tokens;
    private final AuditLog audit;
    private final Db db;
    private final AdminProperties properties;
    private final String dummyHash;
    /**
     * Last TOTP step used per operator. Per-instance state: correct while one
     * process serves the console. A second instance needs this in a column
     * (Phase 6), or a code could be replayed against the other instance.
     */
    private final ConcurrentHashMap<UUID, Long> lastUsedStep = new ConcurrentHashMap<>();

    public AuthService(OperatorRepository operators, SessionRepository sessions, PasswordHasher hasher,
                       Tokens tokens, AuditLog audit, Db db, AdminProperties properties) {
        this.operators = operators;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.audit = audit;
        this.db = db;
        this.properties = properties;
        this.dummyHash = hasher.hash("timing-equaliser-not-a-password".toCharArray());
    }

    public LoginResult login(String email, String password, String userAgent) {
        var pw = password == null ? new char[0] : password.toCharArray();
        var found = operators.findByEmail(email);
        if (found.isEmpty()) {
            hasher.verify(pw, dummyHash);                 // same work as a real check
            throw Problems.unauthorized("invalid email or password");
        }
        var operator = found.get();
        boolean ok = hasher.verify(pw, operator.passwordHash());
        if (operator.locked()) throw Problems.unauthorized("invalid email or password");
        if (!ok) {
            operators.recordFailedLogin(operator.id());
            throw Problems.unauthorized("invalid email or password");
        }
        if (!operator.active()) throw Problems.unauthorized("invalid email or password");
        if (!operator.mfaEnrolled() && operator.roles().stream().anyMatch(ABOVE_ANALYST::contains)) {
            throw Problems.forbidden("MFA enrolment required for this role");
        }
        if (operator.mfaEnrolled()) {
            return new LoginResult(tokens.issuePurpose(operator.id(), "mfa", Map.of(), MFA_TOKEN_TTL), null);
        }
        operators.clearFailures(operator.id());
        return new LoginResult(null, signIn(operator, userAgent));
    }

    public Signed mfa(String mfaToken, String code, String userAgent) {
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(tokens.verifyPurpose(mfaToken, "mfa").get("sub")));
        } catch (Tokens.InvalidToken | IllegalArgumentException e) {
            throw Problems.unauthorized("MFA step expired; sign in again");
        }
        var operator = operators.findById(id)
                .filter(Operator::active)
                .filter(o -> !o.locked() && o.mfaEnrolled() && o.mfaSecretEnc() != null)
                .orElseThrow(() -> Problems.unauthorized("invalid code"));

        var secret = SecretBox.decrypt(SecretBox.keyFromHex(properties.mfaKey()), operator.mfaSecretEnc());
        var step = matchedStep(secret, code, Instant.now());
        if (step == null) {
            operators.recordFailedLogin(id);
            throw Problems.unauthorized("invalid code");
        }
        var fresh = new boolean[1];
        lastUsedStep.compute(id, (k, used) -> {
            fresh[0] = used == null || step > used;
            return fresh[0] ? step : used;
        });
        if (!fresh[0]) throw Problems.unauthorized("code already used; wait for the next one");

        operators.clearFailures(id);
        return signIn(operator, userAgent);
    }

    public Signed refresh(String refreshToken, String userAgent) {
        SessionRepository.Issued issued;
        try {
            issued = sessions.rotate(refreshToken);
        } catch (SessionRepository.AuthFailure e) {
            throw Problems.unauthorized("session ended; sign in again");
        }
        var operator = operators.findById(issued.operatorId()).filter(Operator::active).orElse(null);
        if (operator == null) {
            sessions.revokeFamily(issued.familyId(), "operator_inactive");
            throw Problems.unauthorized("session ended; sign in again");
        }
        var access = tokens.issueAccess(operator.id(), operator.roles(), issued.sessionId(), operator.ver());
        return new Signed(access, operator, issued.refreshToken());
    }

    public void logout(String refreshToken) {
        var ref = sessions.refOf(refreshToken).orElse(null);
        if (ref == null) return;
        db.inTx(c -> {
            sessions.revokeFamily(c, ref.familyId(), "logout");
            audit.record(c, ref.operatorId(), "auth.logout", "operator", ref.operatorId().toString(), null, null);
            return null;
        });
    }

    private Signed signIn(Operator operator, String userAgent) {
        return db.inTx(c -> {
            var issued = sessions.issue(c, operator.id(), UUID.randomUUID(), userAgent);
            audit.record(c, operator.id(), "auth.login", "operator", operator.id().toString(), null, null);
            var access = tokens.issueAccess(operator.id(), operator.roles(), issued.sessionId(), operator.ver());
            return new Signed(access, operator, issued.refreshToken());
        });
    }

    /** The 30-second step whose code matches (current ±1, as Totp.verify), or null. Constant-time compare. */
    static Long matchedStep(byte[] secret, String code, Instant now) {
        if (code == null || code.length() != 6) return null;
        long current = Math.floorDiv(now.getEpochSecond(), 30);
        Long matched = null;
        for (int d = -1; d <= 1; d++) {
            var candidate = Totp.code(secret, now.plusSeconds(30L * d));
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                matched = current + d;
            }
        }
        return matched;
    }
}
