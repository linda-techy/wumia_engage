# Admin console v0 — design

**Date:** 2026-09-20
**Status:** approved for planning
**Scope:** `admin-api` (new module) + `admin-ui` (new Angular app), on Phase 1 data only

---

## Why v0 and not Phase 6

`docs/technical/phase-6-admin-console-campaigns.md` assumes Phases 2–5 exist. Its dashboards, WhatsApp health, push health, journey inspector and campaign screens read `sends`, `cascade_attempts`, `channel_capability` and `devices` — tables that stay empty until sending ships. Building them now produces blank screens and rework.

v0 builds the parts that have data today: authentication, and four screens over the Phase 1 ingest tables. It is the shell Phase 6 screens slot into, not a throwaway: same modules, same patterns, same auth.

**Out of scope for v0:** campaigns, segments, templates, journeys, config editing, CSV export, charts, operator management beyond the bootstrap owner.

---

## 1. Backend — `admin-api`

New Gradle module, Micronaut 5 / Java 25, port `ADMIN_API_PORT` (default 8083), same Postgres and same `config/local.env` as `ingest-api`. Separate service by design: a webhook outage and a console outage are independent.

### 1.1 Refactor first: `core-persistence`

`Db`, `Sql` and `SqlFiles` live in `ingest-api` today. `admin-api` needs them; depending on `ingest-api` inverts the dependency direction. Extract them into a new `core-persistence` module that both services depend on. Mechanical move, no behaviour change; the existing `ingest-api` tests prove it.

### 1.2 Auth

Implements `docs/03-auth-and-rbac.md` against the existing `V2__admin_and_config.sql` schema. No new migration is required for v0.

| Concern | Decision |
|---|---|
| Passwords | Argon2id via BouncyCastle (pure Java, no native dependency) |
| MFA | TOTP (RFC 6238) implemented in `core-domain`, framework-free and unit-tested |
| MFA secret at rest | AES-GCM, key from `ADMIN_MFA_KEY` (env), stored in `operators.mfa_secret_enc` |
| Access token | RS256 JWT, 15 min, claims `sub`, `roles`, `sid`, `ver` |
| Signing key | `config/admin-jwt.pem`, generated once by a script, git-ignored |
| Refresh token | 32 random bytes, stored as `sha256`, 14 days, rotated on every use |
| Reuse detection | A rotated token presented again revokes the whole family and writes an audit row |
| Throttling | 5 failures → 15-minute lock (`failed_logins`, `locked_until`); identical response and timing for unknown email and wrong password |
| MFA mandate | Enforced server-side: no role above `ANALYST` without `mfa_enrolled_at` |
| Audit | Every mutation writes `audit_log` **in the same transaction** |

Refresh tokens are returned in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. Access tokens are returned in the response body only.

### 1.3 Bootstrap

`BootstrapOwner` runs at startup: if `operators` is empty and `ADMIN_BOOTSTRAP_EMAIL` is set, create an `invited` OWNER and log a one-time set-password link. It never runs again once any operator exists.

The set-password link carries a short-lived JWT signed with the same RS256 key, claiming `sub` (operator id), `pwd` (the operator's current `password_changed_at`) and a 1-hour expiry. `POST /auth/set-password` accepts it only while `pwd` still matches the row, so setting a password invalidates the link that set it. No new table and no new migration: the invalidation is a property of data the schema already holds.

### 1.4 Endpoints

Base path `/api`, JSON only, `Authorization: Bearer`, RFC-9457 `problem+json` errors, cursor pagination where lists can grow.

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/auth/login` | — | Password step; returns `mfaToken` when enrolled |
| POST | `/auth/mfa` | — | TOTP step; returns token pair |
| POST | `/auth/refresh` | — | Rotates; reuse revokes the family |
| POST | `/auth/logout` | any | Revokes the family |
| GET | `/auth/me` | any | Operator, roles, MFA state |
| POST | `/auth/mfa/enrol` | any | Provisioning URI + recovery codes |
| POST | `/auth/set-password` | — | One-time token from the bootstrap link |
| GET | `/ingest/health` | VIEWER | Inbox counts by topic and state, stuck items, lag |
| GET | `/customers?q=` | VIEWER | Exact-match lookup, at most one identity |
| GET | `/customers/{id}` | VIEWER | 360 view: keys, profile, consent, orders, checkouts, payments |
| POST | `/customers/{id}/reveal` | ANALYST | Returns unmasked contact values; writes an audit row |
| GET | `/payments/failures` | VIEWER | Match report + recent attempts |
| GET | `/consent-copy` | VIEWER | Registered versions with grant counts |
| POST | `/consent-copy` | CONFIG_ADMIN | Register a version; immutable once written |

---

## 2. Frontend — `admin-ui`

Angular 22, standalone, zoneless, signals-first, per `docs/06-admin-ui-angular.md`. Requires Node 22 (nvm).

```
admin-ui/src/app/
├─ core/
│  ├─ auth/      TokenService (in-memory access token), interceptor, guards
│  ├─ api/       hand-written typed clients, one per feature
│  └─ layout/    shell, nav, operator menu
├─ features/
│  ├─ auth/      login, MFA challenge, MFA enrolment, set password
│  ├─ ingest/    ingest health
│  ├─ customers/ lookup + 360 view with consent timeline
│  ├─ payments/  failure report
│  └─ consent/   consent copy registry
└─ shared/       en-IN money/date pipes, masked PII, tables, empty/error states
```

**Deviation from the spec, deliberate:** API clients are hand-written typed services rather than generated from Micronaut's OpenAPI output. v0 has ~13 endpoints; the generation pipeline is disproportionate plumbing at this size. Introduce it in Phase 6, when the contract grows. Everything else follows the spec, so this is additive later, not a rewrite.

Reads use `httpResource` (loading and error as signals). Writes use Signal Forms. Routes lazy-load and are role-guarded, with the guard understood as a UX affordance while the server enforces roles.

Access token in a signal, never `localStorage`; refresh token in the `HttpOnly` cookie. One silent refresh on page load. The interceptor retries a 401 once and shares a single in-flight refresh across parallel requests.

Tailwind plus CDK a11y primitives; no component library. CDK virtual scroll on tables from the start. No charts in v0.

Dev: `ng serve` on 4200 proxying `/api` to 8083.

---

## 3. Data access and PII

The threat model ranks customer-list exfiltration second only to an unauthorised campaign. v0 removes the bulk path rather than rate-limiting it.

- **Exact-match lookup only.** `/customers?q=` takes a full email or phone and returns at most one identity. No wildcard search, no customer listing, no pagination through the base.
- **Masked by default.** Phones render through `Msisdn.mask` (`+91 98•••••210`); emails are masked similarly. **Reveal** requires `ANALYST`, returns the full value, and writes an audit row naming operator, identity and time.
- **No export in v0.**
- **Queries** are hand-written SQL files loaded through `SqlFiles`, read-only except consent-copy registration.

Screen → source:

| Screen | Reads |
|---|---|
| Ingest health | `webhook_inbox` aggregates: by topic and state, oldest unprocessed, processing lag |
| Customer 360 | `identity_keys`, `profiles`, `consent_current` + consent history, `checkouts`, `orders`, `payment_attempts` |
| Payment failures | `payment_failure_match_report`, recent `payment_attempts` |
| Consent copy | `consent_copy_versions` + grant counts |

The console's database is configuration, so one UI can point at the live instance or the dev-store instance.

---

## 4. Testing

Backend tests run against real Postgres (`wumika_admin_test`). Never H2, per `CLAUDE.md` §10.

**Auth (the `CLAUDE.md` list plus v0 additions):**
- refresh reuse revokes the family
- expired refresh rejected
- `CAMPAIGN_EDIT` gets 403 where `CONFIG_ADMIN` is required
- no role above `ANALYST` without MFA enrolled
- TOTP code reuse within one step rejected
- password change bumps `ver` and invalidates outstanding access tokens
- 5 failed logins lock the account for 15 minutes
- unknown email and wrong password are indistinguishable in body and timing
- a set-password link stops working once the password has been set

**Screens:**
- reveal writes an audit row naming operator and identity
- lookup with a partial email returns nothing (no wildcard behaviour)
- a second write to an existing consent-copy version is rejected
- ingest health counts match rows inserted by the test

**Frontend:** Vitest for TokenService, interceptor (including the shared in-flight refresh) and guards. One Playwright run: login → MFA → lookup → reveal → logout.

---

## 5. Build order

Each step is usable before the next begins.

1. `core-persistence` extraction; `admin-api` skeleton with `/health`
2. Auth: password, MFA, tokens, roles, audit, bootstrap owner
3. Angular shell: login, MFA, guarded empty shell
4. Ingest health, end to end
5. Customer lookup and 360 view, with masking and reveal
6. Payment failures
7. Consent copy registry

Steps 2 and 5 are the bulk; 6 and 7 are small. This is several sessions of work.

---

## 6. New configuration

Added to `config/local.env` and `config/local.env.example`:

| Key | Purpose |
|---|---|
| `ADMIN_API_PORT` | Default 8083 |
| `ADMIN_JWT_KEY_FILE` | RS256 keypair path, default `config/admin-jwt.pem`, git-ignored |
| `ADMIN_MFA_KEY` | AES-GCM key for MFA secrets at rest |
| `ADMIN_BOOTSTRAP_EMAIL` | First OWNER, used once |

---

## 7. Open questions

None blocking. Two decisions are deliberately deferred to Phase 6: OpenAPI client generation, and CSV export with its throttling and audit design.
