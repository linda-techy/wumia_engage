# Implementation plan — phase by phase

**What this is:** the build plan, broken into tasks small enough for one Claude Code session each. Every task names the files it creates, the migration it adds, the tests that must pass, and the command that proves it is done.

**How it relates to the other documents**

| Document | Answers |
|---|---|
| `CLAUDE.md` | The rules. Invariants no task may break. |
| `docs/technical/phase-*.md` | The *design*: what each phase builds and why. |
| **`docs/implementation/P*.md`** | **The *work*: which task, which files, in what order, done when.** |
| `LOCAL-SETUP.md` | Running it on your machine. |

When a task and a design doc disagree, the task wins, because it was written against the code that exists. When a task and `CLAUDE.md` disagree, `CLAUDE.md` wins and the task has a bug.

---

## Phase status

| Phase | File | Weeks | Status |
|---|---|---|---|
| 0 | [P0 — Prerequisites](P0-prerequisites.md) | 0–1 | ☐ Not started. **Start day 1**: external approvals set the critical path. |
| 1 | [P1 — Core platform](P1-core-platform.md) | 1–2 | ◐ **Built and verified** (V1–V6, ingest-api). Gap tasks: T01 done; T03 done (inventory → `variant_restocked`, V9; verified on the dev store); T04 done (cancelled, paid, refunds, `price_dropped`, uninstall logged; the uninstall halt waits for P6-T02); T05 done (inbox housekeeping, `/prometheus` behind `METRICS_TOKEN`); T02 done (Shopify fulfillments + Shiprocket, ADR-005); T06: no staging; prod on AWS later; migrate job and memory caps live on dev; dev-server rebuild checklist `deploy/REBUILD.md`. |
| 2 | [P2 — Shopify storefront + FCM push](P2-shopify-storefront-push.md) | 2–4 | ◐ T01–T03 done; first dev-store subscriber and a test push delivered. T04 built, JS unit tests done (18, in CI; device matrix in T09 remains); T06 built and active on wumika-dev (web pixel + `/pixel/events`; checkout check pending); T08 done (nightly dormant sweep, V11 views, `engage_push_tokens` gauge on the worker); T05 built (Thank you page WhatsApp block, session-token verifier, V12 copy; dev-store check pending); T09 open; T07 has `push_v1` only |
| 3 | [P3 — Policy engine + push sending](P3-policy-and-push.md) | 4–5 | ◐ T01–T06 done (policy engine, migration V8; templates as code; FCM adapter, real push delivered to a dev device; router + orchestrator; worker; signed click beacons, UTMs, ArchUnit). T07: all four intents built (`cart_recovery`, `back_in_stock`, `price_drop`, `browse_abandon`); dev-store checks for the last two pending |
| 4 | [P4 — WhatsApp, SMS, orchestrator](P4-whatsapp-sms-orchestrator.md) | 5–7 | ☐ Blocked on Meta verification + DLT |
| 5 | [P5 — Journeys](P5-journeys.md) | 7–9 | ☐ Blocked on the Razorpay spike (P0-T06) |
| 6 | [P6 — Admin console + campaigns](P6-admin-console-campaigns.md) | 9–11 | ☐ |
| 7 | [P7 — Email, measurement, go-live](P7-email-measurement-golive.md) | 11–12 | ☐ |

Team assumption: two backend engineers (BE1, BE2) and one frontend engineer (FE). Growth or ops owns the approvals in P0.

---

## Where the code goes

The repository grows into this layout. Modules are added in the phase shown; nothing is created before it is needed.

```
engage/
├─ core-domain/         P1 ✓  pure logic: signatures, phones, money, verifiers
├─ persistence/         P3 ✓  Db, Sql, SqlFiles, IdentityResolver (extracted from ingest-api)
├─ ingest-api/          P1 ✓  webhooks + storefront endpoints (Shopify, Razorpay, Meta, courier, pixel)
├─ policy/              P3 ✓  PolicyEngine, ConfigResolver, KillSwitch, Holdouts
├─ channels/            P3 ◐  FCM ✓; ChannelAdapter + FCM (P3), WhatsApp + SMS (P4), SES (P7)
├─ orchestrator/        P3 ◐  templates ✓, router + single-step orchestrator ✓; MessageOrchestrator, MessageRouter, cascades (P4), CapabilityService (P4)
├─ journeys/            P5    journey definitions: event → intent
├─ worker/              P3 ◐  event dispatch, tick, sweeper ✓; Micronaut app: event dispatch, cascade ticker, campaign executor, jobs
├─ admin-api/           P6 ◐  Micronaut app: operators, RBAC, config, campaigns, reports. Console v0 built (auth + MFA, ingest health, customer 360 + reveal, payment failures, consent copy)
├─ admin-ui/            P6 ◐  Angular 22. Console v0 built: sign-in + MFA (QR enrolment), ingest health, customer 360 + audited reveal, payment failures, consent copy
├─ shopify-extension/   P2    Shopify CLI app: theme app extension, web pixel, Thank-you extension
├─ templates/           P3 ✓  message templates as code (YAML), linted in CI
└─ db/migration/        V1–V6 ✓, then one migration per phase (below)
```

**Dependency direction** (enforced by ArchUnit from P3): `core-domain` ← `persistence` ← `policy`, `channels` ← `orchestrator` ← `journeys` ← `worker`, `admin-api`. `channels` never imports `policy` or `orchestrator`. Only the router touches a channel adapter.

### Migrations per phase

| Version | Phase | Contents |
|---|---|---|
| V1–V6 | P1 ✓ | core, admin & config, Shopify & push, orchestrator tables, Razorpay payments, identity functions |
| V7 | P2 ✓ | `push_v1` consent copy registered (took V7 before this table was renumbered; forward-only, so later versions moved up by one) |
| V8 | P3 ✓ | `events.dispatched_at` + claim index; `config_keys.default_value`; kill-switch, push and `holdout.journey_pct` config keys; `config_changed` notify trigger (planned as V10; built before the P1 gaps and P2 views, so it took the next free number and those two moved up) |
| V9 | P1 gaps ✓ | `shipments`, `shipment_events`, `order_refunds`, `inventory_levels`, `variant_prices`; cancellation and refund columns on `orders`; `inventory_state.product_id` (added with P1-T03) |
| V10 | P3-T07 ✓ | index on pixel events by `client_id` (browse_abandon session lookup). Built before the P2 views, so it took V10 and the planned P2–P7 migrations below moved up by one |
| V11 | P2 ✓ | `push_prompt_funnel` view; `device_health` (stale/active/inactive) view, built with P2-T08 |
| V12 | P2-T05 ✓ | `checkout_notice_v1` (WhatsApp order updates on the checkout phone notice) and `ty_wa_v1` (Thank you page opt-in incl. offers). Built before P4, so the planned P4–P7 migrations below moved up by one more |
| V13 | P2 ✓ | `push_v2` soft-ask copy (order updates + alerts: transactional and marketing), with the early ask |
| V14 | P2-T07 ✓ | `wa_v1` (cart checkbox) and `wa_inthread_v1` (wa.me message) WhatsApp copy, both incl. offers |
| V15 | P2 ✓ | `checkout_notice_v2`: the checkout phone label names offers too (WhatsApp marketing opt-out, decided 2026-10-01) |
| V16 | P6-T02 ✓ | dashboard views (`dash_sends_24h`, `dash_spend_today`, `dash_capability`, `dash_consent_daily`, `dash_journey_health`, `dash_push_devices`, `dash_wa_templates`) and an index on `sends.created_at`. Built before P4, so the planned P4–P7 migrations below moved up by one more |
| V17 | P6-T03 ✓ | `json_schema` bounds on every config key (WhatsApp marketing ≤ 2/day, holdouts ≤ 50 %, FCM TTL ≤ 28 days, …); one pending proposal per key and selector. Built before P4: the planned P4–P7 migrations below moved up by one more |
| V18 | P6-T04 ✓ | `pii_unmask_log` (append-only), for the journey inspector; taken from the planned P6 admin-security migration |
| next | P4 | `wa_phone_numbers` + history (quality, tier); WhatsApp config keys |
| next | P5 | journey parameter config keys with defaults; `profile_recompute()` |
| next | P6-T08 | `exports`, `operator_recovery_codes` |
| next | P7 | `holdout_lift_weekly`, `spend_by_intent_daily`; `email.daily_cap`; `consent_erasure_proofs` + `erase_identity()` |

Migrations are forward-only. Never edit one that has run anywhere but your laptop. Fix forward with the next version.

**Planned migrations have no number.** A migration takes the next free version when it is built (`V<next>__name.sql` in the phase docs). Fixed numbers were renumbered four times as work landed ahead of plan, and Flyway refuses a lower version once a database has a higher one.

### Where the plan deliberately differs from the design docs

These were found while checking the plan against the schema that exists. The tasks carry the corrected version.

| Design doc says | Plan does | Why |
|---|---|---|
| Router is one `@Transactional` method around the provider call (`04-backend`) | Three steps: record → call provider outside any transaction → mark result (P3-T05) | A slow FCM or Meta call must not pin a pool connection |
| Any permanent provider error adds a suppression (`04-backend`) | Per-code effect on the exception; 131026/131049 never suppress (P4-T02) | A suppression on 131026 is the silent customer loss `CLAUDE.md` §4.1 forbids |
| Config defaults seeded as `config_versions` rows | `config_keys.default_value` (V8) | `config_versions.changed_by` must be a real operator |
| Micronaut Data repositories and Testcontainers | Plain JDBC + SQL files; tests on local `engage_test` / CI service container | Matches the Phase 1 code that exists and was verified |
| Journeys cancel on `checkout_started`, `cart_emptied` | Those events are added to `ShopifyInboxHandler` in P5-T01 | Phase 1 did not emit them |

Every planned migration block in these files (V8 onward) has been applied in order on PostgreSQL 16 on top of V1–V6, followed by `db/tests/invariants.sql` (still 17/17) and a functional check of the new views and `profile_recompute()`.

---

## Working with Claude Code

### One task per session

Each task has an ID (`P3-T04`) and a **Claude Code prompt**. Start every session with this preamble, then paste the task's prompt:

> Read `CLAUDE.md` and `docs/implementation/README.md`, then the task below in `docs/implementation/<phase file>`. Implement **only** this task. Do not start the next one.
> Rules: follow every invariant in `CLAUDE.md`. Write the listed tests first, and make them fail for the right reason before making them pass. Never use H2: tests run against Postgres (`TEST_DB_NAME` locally, a Postgres service in CI). Keep money in paise (`long`). Keep times in UTC and reason in IST.
> Finish by running the task's **Done when** command, showing its output, and ticking the task's checkbox in the plan file.

Why one task per session: each task fits in context with its design section, and each ends at a green, reviewable state. A session that tries three tasks ends with three half-finished ones.

### Branches and commits

- Branch: `p3/t04-fcm-adapter`
- Commit message starts with the task ID: `P3-T04 FCM adapter with token pruning`
- One PR per task. The PR description pastes the **Done when** output.

### Definition of done (every task)

A task is done when all of these hold:

1. Its listed tests exist and pass, along with every earlier test (`./gradlew build`).
2. `psql -f db/tests/invariants.sql` still passes if it touched the schema.
3. No new warnings (`-Xlint:all` is on).
4. Any new setting is in `config/local.env.example` with a comment, **and** in `LOCAL-SETUP.md` §1.
5. Its checkbox in the plan file is ticked, in the same PR.

### Test tiers

| Tier | Runs against | Command |
|---|---|---|
| Unit | Nothing (pure Java) | `./gradlew :<module>:test` |
| Database | Real Postgres (`engage_test`) | same; tests guard on `_test` suffix |
| End to end | Signed webhooks → Postgres | `./gradlew :ingest-api:test`, then `:worker:test` from P3 |
| Invariants | Any migrated DB | `psql -f db/tests/invariants.sql` (rolls back) |

The pattern that caught most real bugs in Phase 1 was running every handler in **natural, reversed and replayed order** (`CLAUDE.md` invariant 11). Apply it to every new handler and consumer.

---

## Critical path

```
Day 1  ─ P0: Meta verification ─────────────────────────────▶ P4 (week 5)
       ─ P0: DLT entity + header + templates ───────────────▶ P4 SMS (week 5)
       ─ P0: Shopify app + protected customer data ─▶ P1 gaps, P2
Week 1 ─ P1-T01 first real build ─▶ P0-T06 Razorpay spike ─▶ P5 plan (week 7)
Week 2 ─ P1 gaps ─▶ P2 storefront ─▶ P3 policy + push ─▶ P4 ─▶ P5 ─▶ P6 ─▶ P7
```

Two things decide whether this lands in 12 weeks: **Meta verification** submitted on day 1, and the **Razorpay spike** result in week 1. Everything else is engineering on a known path.
