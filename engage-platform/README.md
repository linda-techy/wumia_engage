# Engage — multi-channel engagement platform

WhatsApp + push (own FCM) + email + SMS for an Indian D2C fashion brand on Shopify, prepaid only, payments through Razorpay, with an operator console for campaigns, policy configuration and audit.

**Micronaut 5 · Java 25 · Postgres 16 · Angular 22 · Shopify (theme app extension + App Proxy) · Razorpay**

No CleverTap, no MoEngage, no WebEngage, no WhatsApp BSP markup.

---

## Start here

| | |
|---|---|
| [**`LOCAL-SETUP.md`**](LOCAL-SETUP.md) | **Run it: local Postgres, your credentials, webhooks through a tunnel, tests** |
| [`CLAUDE.md`](CLAUDE.md) | Engineering contract and invariants. Claude Code reads this automatically. |
| [Phased technical design](docs/technical/00-overview.md) | The design: Phase 0–7 with code, acceptance criteria and exit gates |
| [**Implementation plan**](docs/implementation/README.md) | **The work, task by task: files, migrations V7–V13, tests, *Done when*, and a Claude Code prompt per task** |
| [`INDIA-PLAYBOOK.md`](INDIA-PLAYBOOK.md) | Consumer psychology, intent catalogue, copy |

## What is built

Phase 1 is implemented and verified against PostgreSQL 16:

```
engage/
├─ config/local.env.example     ← copy to config/local.env; the ONE place for credentials
├─ scripts/
│  ├─ db-setup.sql              ← creates the app login + databases (run once)
│  └─ send-test-webhook.sh      ← sends signed Razorpay/Shopify fixtures locally
├─ db/
│  ├─ migration/V1–V6           ← schema: identity, consent, inbox, Shopify, cascades,
│  │                               Razorpay payments, identity resolution functions
│  └─ tests/invariants.sql      ← 17 database rules, rolls back after running
├─ core-domain/                 ← signatures, phone numbers, money (+ 40 unit tests)
└─ ingest-api/                  ← Micronaut service: Shopify + Razorpay webhooks →
                                   inbox → identities, checkouts, orders, payments, consent
```

Phases 2–7 are designed in `docs/technical/` and broken into tasks in `docs/implementation/`; they are not yet built. Next task: **P1-T01**, the first real `./gradlew build`.

## Reference documents

| | |
|---|---|
| [01 — Architecture](docs/01-architecture.md) | Topology, module layout, why Micronaut, request paths |
| [02 — Config and settings](docs/02-config-and-settings.md) | Versioned policy config, risk tiers, kill switches |
| [03 — Auth and RBAC](docs/03-auth-and-rbac.md) | Operator login, MFA, token rotation, roles, four-eyes |
| [04 — Micronaut backend](docs/04-backend-micronaut.md) | Policy engine, router, repositories, workers, tests |
| [05 — Campaigns](docs/05-campaigns.md) | Lifecycle, dry run, execution, throttling, reporting |
| [06 — Admin console](docs/06-admin-ui-angular.md) | Angular 22 signal-first app, composer, generated settings |
| [07 — API contract](docs/07-api-contract.md) | Endpoints, roles, payloads, rate limits, webhooks |
| [08 — Deployment and ops](docs/08-deployment-and-ops.md) | Build, scaling, migrations, alerts, runbooks |
| [`db/migration/`](db/migration/) | Flyway migrations V1–V6 (V2: operators, RBAC, versioned config, campaigns, audit) |

---

## The three ideas

Everything else is implementation detail.

### 1. One door

Nothing sends except through the policy engine. Not a journey step, not a campaign executor, not a controller, not a migration script. Consent, frequency caps, quiet hours and budget are enforceable only if there is exactly one place they can be checked.

This is why the campaign executor calls the same `router.send()` as everything else. An operator with `CAMPAIGN_SEND` cannot bypass policy — the console proposes work, it does not override rules. Needing a higher cap is a config change with its own approval and audit trail.

### 2. Config is evidence, not preference

A `settings` table with a mutable `value` column cannot answer "why did this customer get a marketing message at 22:40 on 3 August". The row only knows today's value.

So config is append-only and versioned, with the actor, a mandatory reason and an effective-dated window. Every send records the config snapshot that permitted it. Three years later the decision is fully reconstructable.

The effective dating pays for itself at festive season: a cap raised for Diwali week reverts on its own, instead of quietly becoming permanent because nobody remembered to change it back.

### 3. Blocked sends are data

A `sends` row with `status='blocked'` and a reason is not waste. It is the answer to "why didn't this customer hear from us", the denominator for holdout analysis, and the evidence a Meta quality review or a DPDP grievance asks for.

It is also the single most useful screen in the console. When a campaign dry run shows 9,140 recipients blocked for `NO_MARKETING_CONSENT`, that is not a campaign problem — your opt-in capture is broken and no campaign will fix it.

---

## Why these decisions

**Micronaut over Spring Boot.** Compile-time DI means ~200ms start on ~90MB heap. The worker fleet scales from 2 to 20 pods during a sale and back down; paying an 8-second JVM warm-up on every scale event is a real cost. Java 25 virtual threads suit an IO-bound workload without the reactive readability tax.

**Postgres only — no Redis, no Kafka in v1.** `FOR UPDATE SKIP LOCKED` is a genuinely good queue to a few hundred thousand jobs a day. One datastore means one backup story and transactional guarantees across "record the send" and "book the spend". Add Kafka when volume demands it; the ingest layer already funnels through one function, so the swap is contained.

**Angular signals, no NgRx.** This is a server-state app — almost everything on screen is a cached database row. `httpResource` plus a few signals covers it. A global store would duplicate server state and add a second source of truth.

**Roles fixed in code.** Editable role definitions are a privilege-escalation surface. The `CAMPAIGN_EDIT` / `CAMPAIGN_SEND` split matters more than any amount of granularity: drafting a campaign and firing it at 200,000 people are different risk levels, and most of the team needs only the first.

---

## Cost context

Meta bills per delivered template message by category. India rates at the time of writing: **marketing ≈ ₹0.8631, utility and authentication ≈ ₹0.115**. Marketing is roughly 7–8× utility, and the 24-hour service window becomes billable from 1 October 2026.

Three mechanisms respond to that:

- **Category validation at build time.** Promotional language inside a utility template fails CI. Meta would re-classify or reject it, and a rejection on a live order-update template breaks transactional flow, not just a campaign.
- **Service-window arbitrage.** Any WhatsApp inbound opens a 24-hour window; a marketing template flagged `serviceVariant` downgrades to a free-form service message inside it.
- **Channel arbitration.** Push → email → WhatsApp → SMS, first channel policy allows. Journeys express intent; the router picks the cheapest viable channel.

Rates live in config, not code. Verify against your own rate card — they move, and the January 2026 India marketing increase was roughly 10%.

---

## Build order

Twelve weeks, two backend engineers and one frontend engineer. Full detail in [`docs/technical/`](docs/technical/00-overview.md).

| Weeks | Phase | Deliverable |
|---|---|---|
| 0–1 | 0 | Meta verification, DLT, Firebase, Shopify app, payment-signal spike |
| 1–2 | 1 | Schema, identity, consent ledger, Shopify webhooks, App Proxy verification |
| 2–4 | 2 | **Shopify web push via FCM** + WhatsApp opt-in capture |
| 4–5 | 3 | **Policy engine and router.** First push intents. Test this hard. |
| 5–7 | 4 | WhatsApp, capability learning, DLT SMS, cascades, order tracking |
| 7–9 | 5 | Payment recovery, checkout abandon, cart recovery, fit check, NDR, win-back |
| 9–11 | 6 | Admin console, RBAC, config, campaigns |
| 11–12 | 7 | Marketing email, holdout measurement, hardening, go-live |

The critical path runs through Phase 0 approvals, not through code. Submit the Meta verification and DLT applications on day one.

Phase 3 is the one not to rush. The policy engine protects your Meta quality rating, and a regression there is invisible until the rating drops — at which point your daily send limit may already have been cut.
