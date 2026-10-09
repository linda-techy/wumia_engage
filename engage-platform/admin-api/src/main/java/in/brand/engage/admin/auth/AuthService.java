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
import java.util.List;
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
 * <li>A recovery code may stand in for the TOTP code, once each (P6-T08).</li>
 * <li>A login's session and its audit row commit in one transaction.</li>
 * </ul>
 */
@Singleton
public class AuthService {

    /** Roles that may never sign in on a password alone. */
    static final Set<String> ABOVE_ANALYST = Set.of("CAMPAIGN_EDIT", "CAMPAIGN_SEND", "CONFIG_ADMIN", "OWNER");
    static final Duration MFA_TOKEN_TTL = Duration.ofMinutes(5);
    static final Duration ENROL_TOKEN_TTL = Duration.ofMinutes(15);
    static final int MIN_PASSWORD = 12;
    static final String ISSUER = "Engage";

    /** What an authenticator app needs: the otpauth URI (QR) and the secret, Base64. */
    public record Enrolment(String provisioningUri, String secret) {}

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
    private final RecoveryCodes recoveryCodes;
    private final String dummyHash;
    /**
     * Last TOTP step used per operator. Per-instance state: correct while one
     * process serves the console. A second instance needs this in a column
     * (Phase 6), or a code could be replayed against the other instance.
     */
    private final ConcurrentHashMap<UUID, Long> lastUsedStep = new ConcurrentHashMap<>();

    public AuthService(OperatorRepository operators, SessionRepository sessions, PasswordHasher hasher,
                       Tokens tokens, AuditLog audit, Db db, AdminProperties properties, RecoveryCodes recoveryCodes) {
        this.operators = operators;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.audit = audit;
        this.db = db;
        this.properties = properties;
        this.recoveryCodes = recoveryCodes;
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
            // No session, but a token that can do one thing: enrol MFA. Without
            // it a new OWNER could never sign in to enrol in the first place.
            throw Problems.forbidden("mfa-enrolment-required", "MFA enrolment required for this role",
                    Map.of("enrolToken", tokens.issuePurpose(operator.id(), "mfa-enrol", Map.of(), ENROL_TOKEN_TTL)));
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

        if (RecoveryCodes.looksLikeOne(code)) {
            // A recovery code instead of the authenticator: lost or replaced phone.
            boolean spent = db.inTx(c -> {
                if (!recoveryCodes.consume(c, id, code)) return false;
                audit.record(c, id, "auth.recovery_code_used", "operator", id.toString(), null, null);
                return true;
            });
            if (!spent) {
                operators.recordFailedLogin(id);
                throw Problems.unauthorized("invalid code");
            }
            operators.clearFailures(id);
            return signIn(operator, userAgent);
        }

        var secret = SecretBox.decrypt(mfaKey(), operator.mfaSecretEnc());
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

    /** The operator an enrolment-only token was issued to (login's 403 for an unenrolled admin). */
    public UUID enrolee(String enrolToken) {
        try {
            return UUID.fromString(String.valueOf(tokens.verifyPurpose(enrolToken, "mfa-enrol").get("sub")));
        } catch (Tokens.InvalidToken | IllegalArgumentException e) {
            throw Problems.unauthorized("enrolment link expired; sign in again");
        }
    }

    /**
     * Stage a new TOTP secret. Not active until {@link #confirmEnrolment}: a
     * secret nobody proved they hold must not count as MFA.
     */
    public Enrolment startEnrolment(UUID operatorId) {
        var operator = operators.findById(operatorId).orElseThrow(() -> Problems.unauthorized("not authenticated"));
        var secret = Totp.generateSecret();
        db.inTx(c -> {
            operators.stageMfaSecret(c, operatorId, SecretBox.encrypt(mfaKey(), secret));
            audit.record(c, operatorId, "auth.mfa_enrol_started", "operator", operatorId.toString(), null, null);
            return null;
        });
        return new Enrolment(Totp.provisioningUri(ISSUER, operator.email(), secret),
                java.util.Base64.getEncoder().encodeToString(secret));
    }

    /** @return the ten recovery codes, to show once */
    public List<String> confirmEnrolment(UUID operatorId, String code) {
        var operator = operators.findById(operatorId).orElseThrow(() -> Problems.unauthorized("not authenticated"));
        if (operator.mfaSecretEnc() == null) throw Problems.badRequest("start enrolment first");
        var secret = SecretBox.decrypt(mfaKey(), operator.mfaSecretEnc());
        var step = matchedStep(secret, code, Instant.now());
        if (step == null) throw Problems.unauthorized("invalid code");
        lastUsedStep.merge(operatorId, step, Math::max);   // this code is now spent for sign-in too
        return db.inTx(c -> {
            operators.setMfaSecret(c, operatorId, operator.mfaSecretEnc());
            var codes = recoveryCodes.issue(c, operatorId);
            audit.record(c, operatorId, "auth.mfa_enrolled", "operator", operatorId.toString(), null, null);
            return codes;
        });
    }

    /**
     * A new set of recovery codes, the old set void. Needs a current TOTP code:
     * a stolen session alone must not be able to mint a way past MFA.
     */
    public List<String> reissueRecoveryCodes(UUID operatorId, String code) {
        var operator = operators.findById(operatorId).filter(Operator::active)
                .filter(o -> o.mfaEnrolled() && o.mfaSecretEnc() != null)
                .orElseThrow(() -> Problems.conflict("mfa-not-enrolled", "enrol MFA first"));
        var step = matchedStep(SecretBox.decrypt(mfaKey(), operator.mfaSecretEnc()), code, Instant.now());
        if (step == null) throw Problems.unauthorized("invalid code");
        var fresh = new boolean[1];
        lastUsedStep.compute(operatorId, (k, used) -> {
            fresh[0] = used == null || step > used;
            return fresh[0] ? step : used;
        });
        if (!fresh[0]) throw Problems.unauthorized("code already used; wait for the next one");
        return db.inTx(c -> {
            var codes = recoveryCodes.issue(c, operatorId);
            audit.record(c, operatorId, "auth.recovery_codes_reissued", "operator", operatorId.toString(), null, null);
            return codes;
        });
    }

    /**
     * Set a password from a one-time link (bootstrap owner, invites). The link
     * names the operator's {@code ver} at issue; setting the password moves
     * {@code ver}, so the link dies with its first use, and every session of
     * that operator is revoked.
     */
    public void setPassword(String linkToken, String password) {
        Map<String, Object> claims;
        try {
            claims = tokens.verifyPurpose(linkToken, "set-password");
        } catch (Tokens.InvalidToken e) {
            throw Problems.unauthorized("this link has expired or was already used");
        }
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(claims.get("sub")));
        } catch (IllegalArgumentException e) {
            throw Problems.unauthorized("this link has expired or was already used");
        }
        var operator = operators.findById(id)
                .filter(o -> String.valueOf(o.ver()).equals(String.valueOf(claims.get("pwd"))))
                .orElseThrow(() -> Problems.unauthorized("this link has expired or was already used"));
        if (password == null || password.length() < MIN_PASSWORD) {
            throw Problems.badRequest("password must be at least " + MIN_PASSWORD + " characters");
        }
        var hash = hasher.hash(password.toCharArray());
        db.inTx(c -> {
            operators.setPassword(c, id, hash);
            sessions.revokeAllFor(c, id, "password_set");
            audit.record(c, id, "auth.password_set", "operator", operator.id().toString(), null, null);
            return null;
        });
    }

    private byte[] mfaKey() {
        return SecretBox.keyFromHex(properties.mfaKey());
    }

    private Signed signIn(Operator operator, String userAgent) {
        return db.inTx(c -> {
            var issued = sessions.issue(c, operator.id(), UUID.randomUUID(), userAgent);
            in.brand.engage.persistence.Sql.update(c, "UPDATE operators SET last_login_at = now() WHERE id = ?", operator.id());
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
