# 07 — API contract

Micronaut OpenAPI generates the spec at compile time from the controllers. The Angular client is generated from that spec in CI, so a backend change that breaks the contract fails the frontend build rather than failing at runtime in front of an operator.

```
./gradlew :admin-api:build          # emits build/classes/META-INF/swagger/engage-admin-1.0.yml
npm run api:generate                # openapi-generator → src/app/core/api
```

## Conventions

- Base path `/api`, JSON only, `Authorization: Bearer <access token>`.
- Money is always integer **paise** in a field suffixed `Paise`. Never a float, never a formatted string.
- Timestamps are RFC-3339 UTC. The UI converts to IST for display.
- Lists are cursor-paginated: `?cursor=&size=` returning `{ items, nextCursor }`. No offset pagination — it skips and duplicates rows when the underlying data shifts mid-scroll, which it constantly does here.
- Mutations accept `Idempotency-Key`. Replaying a key returns the original result rather than repeating the action.
- Errors are RFC-9457 `application/problem+json`.

```json
{
  "type": "https://engage.brand.in/errors/estimate-stale",
  "title": "Estimate is stale",
  "status": 409,
  "detail": "Policy config changed since this campaign was estimated.",
  "instance": "/api/campaigns/3f1a.../start",
  "configSnapshotId": 8841,
  "estimateSnapshotId": 8792
}
```

## Auth

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/login` | — | Returns `mfaToken` when MFA is enrolled |
| POST | `/auth/mfa` | — | TOTP verification, returns token pair |
| POST | `/auth/refresh` | — | Rotates. Reuse revokes the family. |
| POST | `/auth/logout` | any | Revokes the family |
| GET | `/auth/me` | any | Operator + roles + MFA state |
| POST | `/auth/mfa/enrol` | any | Returns provisioning URI + recovery codes |

## Config

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/config` | any | Registry + current values + pending proposals |
| GET | `/config/{key}/history` | `ANALYST` | Full version history with actor and reason |
| POST | `/config/{key}` | `CONFIG_ADMIN` | `CRITICAL` keys create a proposal |
| POST | `/config/proposals/{id}/approve` | `CONFIG_ADMIN` | Must differ from proposer |
| POST | `/config/proposals/{id}/reject` | `CONFIG_ADMIN` | Must differ from proposer; optional `{reason}` |
| DELETE | `/config/proposals/{id}` | `CONFIG_ADMIN` | Withdraw (proposer only) |
| GET | `/config/snapshots/{id}` | any | The resolved config a send was decided under |
| GET | `/halt` | any | Halts in force, with actor, reason and since |
| POST | `/halt` | `CAMPAIGN_SEND` or `CONFIG_ADMIN` | Kill switch `{scope: channel\|marketing\|journey, selector, reason}`. Immediate, uncached, no approval. Pauses RUNNING and SCHEDULED campaigns on the scope |
| DELETE | `/halt/{scope}/{selector}?reason=` | `CONFIG_ADMIN` | Release. Follows the key's risk tier; paused campaigns stay paused |
| GET | `/dashboard` | any | Spend vs budget, 24 h sends by reason, journey health, consent, capability, push and template health (one poll) |

```http
POST /api/config/cap.whatsapp.marketing.1d
{
  "selector": "*",
  "value": 2,
  "effectiveFrom": "2026-10-17T00:00:00+05:30",
  "effectiveTo":   "2026-10-23T00:00:00+05:30",
  "reason": "Diwali week — approved by growth and finance, auto-reverts"
}

202 Accepted
{
  "proposalId": "9c2e...",
  "status": "PENDING_APPROVAL",
  "risk": "CRITICAL",
  "requiresApprovalFrom": "a different CONFIG_ADMIN"
}
```

`reason` is required by the schema. A config change without a stated reason is not accepted, because six months later the reason is the only part anyone needs.

## Segments

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/segments/fields` | `VIEWER` | The predicate whitelist: field, operators, value kind, availability |
| GET | `/segments`, `/segments/{id}` | `VIEWER` | With definition and last size |
| POST | `/segments/preview` | `CAMPAIGN_EDIT` | `{definition}` → `{size}`; nothing saved. 10 s limit, else 422 |
| POST | `/segments` | `CAMPAIGN_EDIT` | `{name, description?, definition}`; sized and audited; names unique (case-insensitive) |
| PUT | `/segments/{id}` | `CAMPAIGN_EDIT` | Re-sized and audited with before/after |
| POST | `/segments/{id}/size` | `CAMPAIGN_EDIT` | Recount |

Definitions are JSON (`{all|any: [...]}`, `{not: {...}}`, `{field, op, value}`), compiled on the server; the browser never sends SQL.

## Campaigns

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/campaigns` | `VIEWER` | Filter by status, channel, creator |
| POST | `/campaigns` | `CAMPAIGN_EDIT` | Creates `DRAFT` |
| PATCH | `/campaigns/{id}` | `CAMPAIGN_EDIT` | `DRAFT`/`READY` only; clears the estimate |
| POST | `/campaigns/{id}/estimate` | `CAMPAIGN_EDIT` | Full dry run |
| GET | `/campaigns/{id}/estimate` | `VIEWER` | Last estimate + staleness flag |
| POST | `/campaigns/{id}/approve` | `CAMPAIGN_SEND` | Four-eyes; re-estimates |
| POST | `/campaigns/{id}/start` | `CAMPAIGN_SEND` | Arms and schedules |
| POST | `/campaigns/{id}/pause` | `CAMPAIGN_SEND` | In-flight sends complete |
| POST | `/campaigns/{id}/resume` | `CAMPAIGN_SEND` | |
| POST | `/campaigns/{id}/cancel` | `CAMPAIGN_SEND` | Terminal |
| GET | `/campaigns/{id}/report` | `VIEWER` | Funnel, spend, holdout lift |
| GET | `/campaigns/{id}/recipients` | `ANALYST` | Paginated, PII masked |

```http
POST /api/campaigns/3f1a.../estimate

200 OK
{
  "audienceSize": 48210,
  "deliverable": 31884,
  "estimatedCostPaise": 2750604,
  "configSnapshotId": 8841,
  "blocks": {
    "NO_MARKETING_CONSENT": 9140,
    "FREQUENCY_CAP": 4201,
    "HOLDOUT_GLOBAL": 2410,
    "SUPPRESSED": 412,
    "UNREACHABLE": 163
  },
  "requiresApproval": true,
  "sample": [
    { "identityId": "…", "phone": "+91 98•••••210", "name": "A••• K••••" }
  ]
}
```

The sample is masked. An estimate endpoint that returns 10 full phone numbers is an estimate endpoint that gets used as an export endpoint.

## Journeys

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/journeys` | `VIEWER` | Definitions + live run counts + 7-day funnel |
| GET | `/journeys/{key}/runs` | `VIEWER` | Filter by status; inspect a single run's step history |
| POST | `/journeys/{key}/enable` | `CONFIG_ADMIN` | |
| POST | `/journeys/{key}/disable` | `CONFIG_ADMIN` | Immediate; in-flight steps complete |
| GET | `/journeys/{key}/incrementality` | `ANALYST` | Holdout lift, `?days=30` |

Journey *definitions* are code and are not editable through the API. Their *config* — enabled, caps, holdout percentage, step delays — is config. That line matters: logic changes get code review, and parameter changes get an audit row.

## Customers

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/customers/lookup?phone=` | `VIEWER` | Exact match only. No wildcard browse. |
| GET | `/customers/{id}` | `VIEWER` | 360: profile, consent timeline, sends, orders |
| POST | `/customers/{id}/unmask` | `ANALYST` | Returns full PII, writes an audit row |
| POST | `/inspector` | `ANALYST` | `{phone, reason}`: runs, attempts and sends for one exact number, masked. Writes `pii_unmask_log`; counts toward the unmask limit |
| GET | `/templates` | any | Registry templates with Meta state per language; category mismatches flagged |
| POST | `/customers/{id}/suppress` | `CAMPAIGN_SEND` | Manual suppression |
| POST | `/customers/{id}/erase` | `OWNER` | DPDP erasure; cascades, irreversible |

Lookup is exact-match by design. A prefix search over phone numbers is a database enumeration tool.

The consent timeline is the most useful view on this screen during a complaint: every grant and withdrawal, with source, timestamp and the exact copy shown at the moment of consent.

## Reporting

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/reports/spend` | `VIEWER` | By day/channel/category, `?from=&to=` |
| GET | `/reports/deliverability` | `VIEWER` | Funnel per channel |
| GET | `/reports/blocks` | `VIEWER` | Block reasons over time |
| GET | `/reports/quality` | `VIEWER` | Meta quality per number and per template |
| POST | `/exports` | `ANALYST` | Async. Throttled, audited, signed URL, 15 min TTL. |

## Rate limits

| Scope | Limit |
|---|---|
| `/auth/login` | 5 per account per 15 min, 20 per IP per 15 min |
| `/exports` | 3 per operator per day |
| `/customers/*/unmask`, `/inspector` | 50 per operator per day |
| Everything else | 600 per operator per minute |

The unmask limit is deliberate. An operator legitimately needs to see a customer's number to resolve a complaint; an operator who needs 500 in a day is exporting your list one record at a time.

## Webhooks in

Handled by `ingest-api`, not `admin-api`. Unauthenticated in the session sense, authenticated by signature.

| Source | Path | Verification |
|---|---|---|
| Shopify | `POST /hooks/shopify` | HMAC-SHA256 over the raw body |
| Meta | `GET,POST /hooks/whatsapp` | `X-Hub-Signature-256`, plus `hub.verify_token` on GET |
| Courier | `POST /hooks/courier` | Shared secret header + IP allowlist |
| Storefront | `POST /events`, `/consent` | Signed app-proxy request |

All four verify against the **raw request body**. Any JSON parsing before signature verification breaks the HMAC, and Micronaut will happily deserialise the body first if you let it — bind `byte[]`, not a DTO:

```java
@Post(value = "/hooks/shopify", consumes = MediaType.APPLICATION_JSON)
public HttpResponse<?> shopify(@Body byte[] raw,
                               @Header("X-Shopify-Hmac-Sha256") String hmac,
                               @Header("X-Shopify-Webhook-Id") String deliveryId,
                               @Header("X-Shopify-Topic") String topic) {

    if (!verifier.verify(raw, hmac)) return HttpResponse.unauthorized();
    if (!dedupe.firstSight(deliveryId)) return HttpResponse.ok("duplicate");

    // Acknowledge inside Shopify's 5s timeout, then do the work. Blocking on
    // journey evaluation here means Shopify retries and you process twice.
    events.submitAsync(topic, raw, deliveryId);
    return HttpResponse.ok();
}
```
