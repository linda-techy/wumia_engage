# P6 — Admin console + campaigns · Implementation

**Weeks 9–11 · Owners: BE2 (admin-api), FE (admin-ui), BE1 (segments, executor) · Design:** [`technical/phase-6-admin-console-campaigns.md`](../technical/phase-6-admin-console-campaigns.md), [`02`](../02-config-and-settings.md), [`03`](../03-auth-and-rbac.md), [`05`](../05-campaigns.md), [`06`](../06-admin-ui-angular.md), [`07`](../07-api-contract.md)

## Outcome

Operators log in with MFA, see the system's health, halt it in an incident, change policy config through proposals and approvals, and run push and WhatsApp campaigns with a dry run, four-eyes approval and a throttled executor. Everything they do is in `audit_log`, written in the same transaction as the change.

**Build order is fixed** (phase-6 §1): auth → kill switches + dashboard → config, copy registry, templates → segments → campaigns. Do not start campaigns before auth and audit are merged.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P6-T01 | `admin-api` app: login, MFA, tokens, RBAC, audit | BE2 | 3 d | P3 done |
| P6-T02 | Kill switches + dashboard endpoints | BE2 | 1 d | T01 |
| P6-T03 | Config API: read, propose, approve, effective dating | BE2 | 1.5 d | T01 |
| P6-T04 | Consent copy registry + template status + journey inspector API | BE2 | 1.5 d | T01 |
| P6-T05 | Segment DSL → SQL compiler | BE1 | 2 d | T01 |
| P6-T06 | Campaigns: estimate, approve, arm, executor | BE1 | 3 d | T05 |
| P6-T07 | Angular 22 admin UI | FE | 8 d (parallel from T01) | T01–T06 endpoints |
| P6-T08 | Exports, PII masking, recovery codes (V18) | BE2 | 1.5 d | T01 |

---

### ☐ P6-T01 — Auth, RBAC, audit

**Files**
```
admin-api/build.gradle.kts                                                    # Micronaut app; micronaut-security-jwt
admin-api/src/main/java/in/brand/engage/admin/auth/AuthController.java        # /auth/login, /auth/mfa, /auth/refresh, /auth/logout
admin-api/src/main/java/in/brand/engage/admin/auth/PasswordHasher.java        # Argon2id
admin-api/src/main/java/in/brand/engage/admin/auth/Totp.java                  # RFC 6238, ±1 step, replay guard
admin-api/src/main/java/in/brand/engage/admin/auth/SessionService.java        # operator_sessions: rotation + family revoke
admin-api/src/main/java/in/brand/engage/admin/auth/Role.java                  # enum, fixed in code
admin-api/src/main/java/in/brand/engage/admin/auth/RequiresRole.java
admin-api/src/main/java/in/brand/engage/admin/audit/Audit.java                # write(Connection, action, target, before, after)
admin-api/src/main/java/in/brand/engage/admin/operators/OperatorController.java
admin-api/src/main/java/in/brand/engage/admin/operators/BootstrapOwner.java   # first OWNER from env, once
```

**Rules** (`03-auth-and-rbac.md`)
- Access JWT RS256, 15 min, claims `sub`, `roles`, `sid`, `ver`. Key pair from `ADMIN_JWT_PRIVATE_KEY_FILE`; public key at `/.well-known/jwks.json`.
- Refresh: opaque 32 bytes, `sha256` in `operator_sessions.refresh_hash`, 14 days, rotated every use, reuse → revoke the family and alert.
- Refresh cookie: `HttpOnly; Secure; SameSite=Strict; Path=/auth`. Access token in memory only.
- MFA required for every role above `ANALYST`. An operator with such a role and no enrolled TOTP can only reach `/auth/mfa/enroll`.
- Login throttling: 5 failures → 15-minute lock per account, plus a per-IP limit. Same response for unknown email and wrong password.
- **Audit in the same transaction.** `Audit.write` takes the caller's `Connection`. A mutation that commits without its audit row is a bug; a test asserts it for every mutating endpoint.
- `BootstrapOwner`: if `operators` is empty and `ADMIN_BOOTSTRAP_EMAIL` is set, create an `invited` OWNER and print a one-time set-password link to the log. It never runs again once any operator exists.

**Tests** (the `03-auth-and-rbac.md` list): refresh reuse revokes the family; expired refresh rejected; `CAMPAIGN_EDIT` gets 403 on `/campaigns/{id}/approve`; MFA-less `CAMPAIGN_SEND` gets 403 everywhere except enrolment; TOTP code reuse in the same step rejected; `ver` bump (password change) invalidates outstanding access tokens.

**Done when:** tests pass, and every controller method in `admin-api` has an explicit `@RequiresRole` (an ArchUnit rule fails the build otherwise).

---

### ☐ P6-T02 — Kill switches + dashboard

- `POST /halt` `{scope: channel|marketing|journey, selector, reason}` → a `config_versions` row for `halt.*` = `true`, **no approval needed** (P3-T02). Allowed for `CAMPAIGN_SEND` and `CONFIG_ADMIN`.
- `DELETE /halt/...` (un-halt) follows the key's normal risk tier and needs `CONFIG_ADMIN`.
- Halting also pauses running campaigns on that scope (their executor sees `CHANNEL_HALTED` anyway, but pausing makes the state visible).
- Dashboard endpoints read views, never raw tables in the controller: sends by status/reason for the last 24 h (IST), spend today vs budget, capability distribution, `push_prompt_funnel`, `device_health`, template mismatches, WhatsApp number quality.

**Done when:** a test halts marketing through the API and the next marketing decision in a worker test context returns `MARKETING_HALTED` within 2 seconds, while utility is allowed. This is the phase-6 acceptance criterion.

---

### ☐ P6-T03 — Config API

- `GET /config` → every `config_keys` row with its effective value (current version or `default_value`), the version that set it, and pending proposals.
- `POST /config/{key}` `{selector, value, effectiveFrom?, effectiveTo?, reason}`:
  - validated against `value_type` and `json_schema`;
  - `SAFE`/`GUARDED` with the key's `min_role` → a `config_versions` row;
  - `CRITICAL` → a `config_proposals` row; `POST /config/proposals/{id}/approve` by a **different** operator inserts the version (the table CHECK enforces `decided_by <> proposed_by`; the API checks it first to return a clear 409).
- `reason` is mandatory and at least 10 characters.
- Every send already records `config_snapshot_id`; `GET /config/snapshots/{id}` shows the whole resolved config at that moment.

**Tests:** CRITICAL self-approval → 409; approved future-dated value takes effect at `effective_from` and not before; a Diwali-week cap with `effective_to` reverts on its own.

---

### ☐ P6-T04 — Copy registry, templates, journey inspector

- `GET/POST /consent-copy` (`CONFIG_ADMIN` to create; rows immutable, V4 trigger). Shows the grant count per version. Replaces the P2-T07 seed file for new versions.
- `GET /templates` joins `templates` and `wa_templates`: requested vs approved category (mismatch flagged), status, quality.
- `GET /inspector?phone=` → exact match on the normalised number (`Msisdn`), returns masked identifiers, every `cascade_runs` row with its `cascade_attempts`, and every `sends` row with `decision`. Each lookup writes a `pii_unmask_log` row (V18) with the operator and reason.

---

### ☐ P6-T05 — Segment DSL

**Files**
```
admin-api/src/main/java/in/brand/engage/admin/segments/SegmentDsl.java          # sealed AST: All | Any | Not | Predicate
admin-api/src/main/java/in/brand/engage/admin/segments/SegmentCompiler.java     # AST → parameterised SQL
admin-api/src/main/java/in/brand/engage/admin/segments/Predicates.java          # the phase-6 §3 field table
```

**Rules**
- The browser sends JSON; the server compiles it. No SQL, no field names, no operators outside the whitelist in `Predicates` reach the query. Every value is a bind parameter.
- Each predicate compiles to an `EXISTS` / `IN` subquery on `identities.id`, so combining them never multiplies rows.
- `older_than` takes an ISO-8601 period (`P60D`), parsed with `java.time.Period`.
- Depth ≤ 5, predicates ≤ 30. A segment count runs with `statement_timeout = 10s`.

**Tests:** the phase-6 §3 example compiles and returns the expected identities on fixture data; an unknown field → 400; a value containing `'; DROP TABLE` is bound, not interpolated (assert on the generated SQL text).

---

### ☐ P6-T06 — Campaigns

Lifecycle and rules from `05-campaigns.md` and phase-6 §4–5.

**Files**
```
admin-api/src/main/java/in/brand/engage/admin/campaigns/CampaignController.java
admin-api/src/main/java/in/brand/engage/admin/campaigns/DryRun.java            # runs PolicyEngine.decide() per recipient, no sends
worker/src/main/java/in/brand/engage/worker/CampaignExecutor.java              # claims campaign_recipients, calls dispatch()
```

- **Estimate** (dry run): resolve the segment, then call the real `PolicyEngine.decide()` for each identity with the campaign's template. Store counts by decision and reason in `campaigns.estimate`, plus `estimated_cost_paise`. Push: stale tokens are their own bucket. WhatsApp: `UNKNOWN` capability is its own bucket and is excluded.
- **Approve:** required when `estimated_cost_paise > approval.required_above_paise`, or the channel is WhatsApp marketing. Approver ≠ author, role `CAMPAIGN_SEND`.
- **Arm:** freeze the audience into `campaign_recipients` (`pending`), and snapshot config. The audience does not change after arming.
- **Execute:** the worker claims `pending` recipients with `SKIP LOCKED`, respecting `campaign_rate_buckets` (token bucket, `send_rate_per_minute`), and calls `orchestrator.dispatch()` with intent `campaign:<id>`. It stops at `budget_cap_paise` and when the campaign is paused. Policy still runs per send: arming is not permission.
- **Multi-channel:** a campaign with a follow-up channel becomes a one-off cascade definition (push, then WhatsApp 6 h later to non-clickers).

**Tests:** author cannot approve their own campaign; a WhatsApp campaign's recipients contain no `UNKNOWN` identity and its template picker offers only APPROVED marketing templates; pause mid-run stops within one claim batch; the executor never exceeds the rate by more than one batch; budget cap stops the run.

---

### ☐ P6-T07 — Angular 22 admin UI

Structure, auth handling and the composer from `06-admin-ui-angular.md`. Signals and `httpResource`; Signal Forms; zoneless; OnPush (the default).

**Screens, in build order**
1. Login, MFA, enrol (QR from the enrol endpoint), silent refresh on load
2. Dashboard + **Halt** buttons (confirmation requires typing the scope name)
3. Settings, generated from `GET /config` (`value_type` → control), with proposals inbox
4. Consent copy registry, templates, push subscriber health, WhatsApp health
5. Journey inspector
6. Segment builder with live count
7. Campaign composer: template → segment → dry run → approval → arm → live progress

**Rules**
- Access token in a service signal only. No `localStorage` for anything security-related.
- Customer-supplied text (names, WhatsApp replies) is rendered with interpolation only, never `innerHTML`.
- Money is displayed from paise with the same lakh grouping as `Paise.toRupeeString` (a small pure function with tests), and times in IST.
- The composer disables **Approve** for the author and **Arm** until a dry run from the last 30 minutes exists.

**Tests:** Vitest for the auth interceptor (refresh once on 401, then log out), money and time formatting, the composer's disabled states; Playwright smoke test for login → dashboard → halt → un-halt against a local admin-api.

**Done when:** `ng build` and `npx vitest run` are green in CI, and the Playwright smoke test passes locally.

---

### ☐ P6-T08 — Exports, PII, recovery codes

**Migration `V18__admin_security.sql`**
```sql
CREATE TABLE operator_recovery_codes (
    operator_id UUID NOT NULL REFERENCES operators(id) ON DELETE CASCADE,
    code_hash   BYTEA NOT NULL,                  -- sha256; codes shown once at enrolment
    used_at     TIMESTAMPTZ,
    PRIMARY KEY (operator_id, code_hash)
);

CREATE TABLE exports (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by UUID NOT NULL REFERENCES operators(id),
    kind         TEXT NOT NULL,                  -- campaign_report | segment_ids | sends
    params       JSONB NOT NULL,
    includes_pii BOOLEAN NOT NULL DEFAULT false,
    row_count    INT,
    status       TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','ready','expired','failed')),
    file_ref     TEXT,                           -- object storage key; link expires in 24 h
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours'
);

CREATE TABLE pii_unmask_log (
    id          BIGSERIAL PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    identity_id UUID,
    field       TEXT NOT NULL,                   -- phone | email | address
    reason      TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- Phones and emails are masked in every API response by default (`Msisdn.mask`). Unmasking is one field at a time, needs a reason, and writes `pii_unmask_log`.
- Exports: `ANALYST`+, 5 per operator per day, PII only for `OWNER` with a reason. Files expire after 24 hours.
- Ten recovery codes at MFA enrolment, each single-use.

---

## Exit gate

- [ ] All phase-6 acceptance criteria ticked
- [ ] Two operators run a real push + WhatsApp campaign on production with four-eyes approval
- [ ] The campaign report reconciles with FCM counts and Meta billing within 2%
- [ ] A halt drill from the UI stops marketing within 2 seconds
