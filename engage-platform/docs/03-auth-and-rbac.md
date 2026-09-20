# 03 — Admin authentication and RBAC

The admin console can spend money, message every customer you have, and read personal data. Treat it as a privileged system, not as a CRUD app with a login page.

## Threat model

What an attacker gets from a compromised operator account, in rough order of damage:

1. **Send a campaign** to your whole list. Costs real money, destroys your Meta quality rating, and cannot be recalled.
2. **Exfiltrate the customer list** — phone numbers, order history, addresses. A DPDP reportable breach.
3. **Raise caps and budgets**, then do 1 without hitting a limit.
4. **Suppress compliance** by disabling opt-out propagation.

The controls below map to these directly: MFA and four-eyes for (1) and (3), export throttling and audit for (2), and making the compliance path non-configurable for (4).

## Login flow

```
POST /auth/login          { email, password }
   → 200 { mfaRequired: true, mfaToken }      when MFA is enrolled
   → 200 { accessToken, refreshToken }        when it is not (VIEWER only)

POST /auth/mfa            { mfaToken, code }
   → 200 { accessToken, refreshToken, operator }

POST /auth/refresh        { refreshToken }
   → 200 { accessToken, refreshToken }        old refresh token is now dead

POST /auth/logout         { refreshToken }
   → 204                                      revokes the whole family
```

**MFA is mandatory for every role above `ANALYST`.** Not optional, not a nag screen. Anyone who can send a campaign or change a cap enrols TOTP or they cannot hold the role. This is the single highest-value control in the list, because it defeats the credential-phishing path that leads to (1) and (3).

## Tokens

**Access token: JWT, 15 minutes, RS256.** Carries `sub`, `roles`, `sid` (session id) and `ver` (the operator's token version). Stateless verification, so the API does not hit Postgres on every request.

**Refresh token: opaque, 14 days, rotated on every use.** 32 random bytes, stored as `sha256(token)`. A database leak yields hashes, not live sessions.

Rotation with reuse detection:

```java
@Transactional
public TokenPair refresh(String presented) {
    var hash = sha256(presented);
    var session = sessions.findByRefreshHash(hash)
            .orElseThrow(() -> new AuthException("unknown refresh token"));

    // Already rotated means this token was captured and replayed. The real
    // user's client also holds a token in this family, so we cannot tell which
    // party is the attacker — kill the whole family and make both re-authenticate.
    if (session.rotatedAt() != null) {
        sessions.revokeFamily(session.familyId(), "refresh_reuse_detected");
        audit.record("auth.refresh_reuse", session.operatorId(), Map.of(
                "family", session.familyId()));
        alerts.securityEvent("Refresh token reuse", session.operatorId());
        throw new AuthException("token reuse detected");
    }

    if (session.revokedAt() != null || session.expiresAt().isBefore(now())) {
        throw new AuthException("expired");
    }

    sessions.markRotated(session.id());
    return issue(session.operatorId(), session.familyId());
}
```

Reuse detection is what turns a stolen refresh token from a persistent backdoor into a 15-minute window plus an alert.

### Where the browser keeps them

Refresh token in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. Access token in memory only — a JavaScript variable in an Angular service, never `localStorage`.

`localStorage` is readable by any XSS on the origin. An admin console renders customer-supplied strings (names, addresses, WhatsApp replies) and one missed escape turns into a token theft. In-memory tokens die with the tab, and the `HttpOnly` refresh cookie restores the session on reload without ever being reachable from script.

The cost is a silent refresh on page load. That is a fair trade.

## Password storage

Argon2id, `m=64MiB, t=3, p=4`. Not bcrypt, not PBKDF2, not SHA-anything.

```java
@Singleton
public class PasswordHasher {
    private final Argon2 argon2 = Argon2Factory.create(Argon2Types.ARGON2id);

    public String hash(char[] raw) {
        try { return argon2.hash(3, 65536, 4, raw); }
        finally { argon2.wipeArray(raw); }
    }

    public boolean verify(String stored, char[] raw) {
        try { return argon2.verify(stored, raw); }
        finally { argon2.wipeArray(raw); }
    }
}
```

Login is rate-limited per account and per IP, with exponential lockout after 5 failures. The response and its timing are identical for "unknown email" and "wrong password", so the endpoint is not a user enumeration oracle.

## Roles

Six roles, fixed in code as an enum. Not editable through the UI.

| Role | Can |
|---|---|
| `VIEWER` | Read dashboards, campaigns, sends, journey runs |
| `ANALYST` | + export (throttled and audited), incrementality, saved segments |
| `CAMPAIGN_EDIT` | + create/edit campaigns, templates, segments. **Cannot send.** |
| `CAMPAIGN_SEND` | + execute and approve campaign sends |
| `CONFIG_ADMIN` | + change policy config, approve `CRITICAL` changes |
| `OWNER` | + manage operators, API keys, destructive operations |

Editable role definitions are a privilege-escalation surface and no team of this size needs them. If you later need per-brand scoping for a multi-brand group, add a `brand_id` dimension to the grant — do not make the roles themselves data.

The `CAMPAIGN_EDIT` / `CAMPAIGN_SEND` split is the important one. Drafting a campaign and firing it at 200,000 people are different risk levels, and most of your team only needs the first.

Roles are additive; a grant carries `granted_by` and `granted_at` and writes an audit row.

## Four-eyes approval

Two paths require a second operator, and in both the approver must be a different person than the author. The check is explicit, because "the author is also an admin" is exactly how this control gets bypassed.

**Campaigns above the spend threshold.** Estimated cost over `approval.required_above_paise` moves the campaign to `PENDING_APPROVAL`. The approver sees the audience size, the estimated spend, the rendered message, and the block-reason breakdown from the dry run.

**`CRITICAL` config changes.** Caps, budgets, holdout percentage, the threshold itself.

```java
@Post("/campaigns/{id}/approve")
@Secured("CAMPAIGN_SEND")
@Transactional
public Campaign approve(@PathVariable UUID id, Principal principal) {
    var campaign = campaigns.require(id);
    var approver = UUID.fromString(principal.getName());

    if (campaign.createdBy().equals(approver)) {
        throw new ForbiddenException("approver must differ from author");
    }
    if (campaign.status() != Status.PENDING_APPROVAL) {
        throw new ConflictException("not pending approval");
    }
    // Estimates go stale: the audience grows, rates change, someone edits the
    // template. Re-estimating at approval time means the approver is looking
    // at the number that will actually be spent.
    var fresh = estimator.estimate(campaign);
    if (fresh.costPaise() > campaign.estimatedCostPaise() * 1.2) {
        throw new ConflictException("estimate moved >20%, re-estimate and re-approve");
    }
    return campaigns.approve(id, approver, fresh);
}
```

## Micronaut wiring

```java
@Singleton
public class OperatorAuthenticationProvider
        implements HttpRequestAuthenticationProvider<?> {

    @Override
    public AuthenticationResponse authenticate(HttpRequest<?> request,
                                               AuthenticationRequest<String, String> req) {
        var email = req.getIdentity();
        var operator = operators.findActiveByEmail(email).orElse(null);

        // Verify against a dummy hash when the account is missing so the
        // response time does not distinguish "no such user" from "bad password".
        var hash = operator != null ? operator.passwordHash() : DUMMY_HASH;
        var ok = hasher.verify(hash, req.getSecret().toCharArray());

        if (operator == null || !ok || operator.isLocked()) {
            if (operator != null) operators.recordFailedLogin(operator.id());
            return AuthenticationResponse.failure("invalid credentials");
        }
        return AuthenticationResponse.success(operator.id().toString(),
                roles.of(operator.id()).stream().map(Enum::name).toList());
    }
}
```

Endpoints are annotated at the controller:

```java
@Controller("/api/config")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class ConfigController {

    @Get                                  // any authenticated operator
    public List<ConfigKeyView> list() { ... }

    @Post("/{key}")
    @Secured("CONFIG_ADMIN")              // role-gated
    public ConfigVersion update(...) { ... }
}
```

Default-deny: `micronaut.security.intercept-url-map` rejects anything not explicitly annotated, so a controller added without a `@Secured` annotation returns 401 rather than being open.

## Data access controls

Role checks stop the wrong person acting. They do not stop the right person exfiltrating, so:

- **Phone and email are masked by default** in list views — `+91 98•••••210`. Unmasking is a per-record action that writes an audit row. A screen that shows 500 full phone numbers is a screenshot away from being a leaked list.
- **Exports are throttled** — 3 per operator per day, capped rows, delivered as a signed URL expiring in 15 minutes, every one audited with its row count.
- **The customer detail view is deep-linked, not browsable.** You can look up a customer you have a reason to look up; you cannot page through the whole database.

## Audit

Every state-changing action writes an `audit_log` row **in the same transaction as the change**. Not in an interceptor, not asynchronously, not best-effort. If the write fails, the action fails — an action without an audit row must not be possible.

`UPDATE` and `DELETE` on `audit_log` are blocked by database rules.

Minimum coverage: login success and failure, MFA enrol and reset, role grant and revoke, config propose/approve/apply, campaign create/estimate/approve/start/pause/cancel, template edit, segment edit, export, PII unmask, kill-switch toggle, API key create and revoke.

The audit view in the UI is filterable by actor, entity and time, and shows a before/after diff. It is read-only for everyone including `OWNER`.
