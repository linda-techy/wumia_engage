# 01 — Architecture

## What this system is

A multi-channel engagement platform for an Indian D2C fashion brand: WhatsApp, web/app push (own FCM), email, SMS. It runs lifecycle journeys (COD confirmation, abandoned cart, back-in-stock, NDR rescue) and manual campaigns, and it is operated through an Angular admin console.

Backend is Micronaut 5 on Java 25. Data is Postgres 16. No CleverTap, no MoEngage, no WhatsApp BSP markup.

## The two invariants

Everything below exists to protect these. If a change would break one of them, the change is wrong.

**1. Nothing sends except through the policy engine.** No controller, no journey step, no campaign executor, no "quick script" calls a channel adapter directly. Consent, frequency caps, quiet hours and budget are only enforceable if there is exactly one door.

**2. Every decision is reconstructable.** A send row records not just what was sent but the config snapshot that permitted it, and blocked attempts are stored with their reason. When Meta asks about a quality-rating drop, or a DPDP grievance lands, or someone asks why a customer got a 22:40 marketing message, the answer is a query — not an archaeology project.

## Why Micronaut

Compile-time dependency injection and AOT mean no runtime classpath scanning, so a worker pod starts in ~200ms on ~90MB heap instead of ~8s on ~400MB. That matters because the worker fleet is the part that scales: journey ticking and campaign execution are embarrassingly parallel, and you want to scale to twenty pods during a Diwali blast and back down to two at 3am without paying a JVM warm-up tax on every scale event.

Java 25 brings virtual threads, which suit this workload exactly. Journey and campaign work is IO-bound — Postgres round-trips and HTTP calls to Meta, SES, FCM — so a virtual-thread-per-task executor gives you high concurrency without the reactive programming tax on readability. Micronaut 5 also offers Scoped Values for context propagation, which is a better fit than thread-locals when work hops between virtual threads.

GraalVM native image is available and the codebase is compatible, but it is optional. Use it for the ingest tier if cold-start latency ever matters; the JVM is fine for the admin API.

## Deployment topology

One codebase, three runtimes, one database.

```
                    ┌──────────────────────────────┐
  Angular admin ───▶│  admin-api        (2 pods)   │
  (browser)         │  JWT auth, RBAC, campaigns,  │
                    │  config, dashboards          │
                    └──────────────┬───────────────┘
                                   │
  Shopify  ────┐                   │
  WhatsApp ────┼───▶┌──────────────▼───────────────┐
  Courier  ────┤    │  ingest-api       (3+ pods)  │
  Storefront ──┘    │  webhooks, event intake,     │───▶ Postgres 16
                    │  consent capture. Stateless. │     (primary + replica)
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────▼───────────────┐
                    │  worker         (2–20 pods)  │
                    │  journey ticker, campaign    │───▶ Meta Cloud API
                    │  executor, profile recompute │───▶ FCM / SES / MSG91
                    └──────────────────────────────┘
```

Three runtimes, not three services. They share `core-domain`, `policy` and `channels` as Gradle modules and deploy from one artifact with different `MICRONAUT_ENVIRONMENTS`. Splitting them into independently-versioned microservices would mean the policy engine exists in three places at three versions, which breaks invariant 1 the first time a deploy is partial.

Scaling characteristics differ enough to justify separate pods: ingest is spiky and latency-sensitive (Shopify times out webhooks at 5s), workers are throughput-bound, and admin-api is low-traffic but holds the most dangerous permissions.

## Module layout

```
engage/
├─ core-domain/      entities, value objects, repositories, no framework logic
├─ policy/           the decision engine + config resolution.        ← the core
├─ channels/         WhatsApp, FCM, SES, SMS adapters behind one interface
├─ journeys/         journey definitions + the durable state machine
├─ ingest-api/       webhook controllers, event intake
├─ admin-api/        operator auth, RBAC, campaigns, config, reporting
├─ worker/           schedulers: journey tick, campaign execute, recompute
└─ admin-ui/         Angular 22 workspace
```

Dependency direction is strictly inward. `channels` does not import `policy`; `policy` does not import `channels`. The router in `core-domain` depends on both through interfaces. This is what stops someone "temporarily" calling WhatsApp from a controller.

## Request paths

**Ingest.** Webhook arrives → HMAC verified against the raw body → deduplicated on the provider's delivery id → acknowledged with 200 inside 5s → event written → journeys evaluated. Acknowledgement happens before journey work, because Shopify retries an unacknowledged webhook and you do not want retries triggering duplicate journeys.

**Journey tick.** Worker claims due runs with `SELECT ... FOR UPDATE SKIP LOCKED`, advances each one step, and writes back. Safe to run on every pod concurrently; no leader election, no distributed lock, no Redis.

**Campaign execute.** Audience is materialised into `campaign_recipients` once at arm time, then workers claim pending rows in batches, rate-limited by a token bucket shared through Postgres. Freezing the audience makes the run resumable and the report accurate; a live segment query mid-send gives you a moving target, duplicate sends and missing recipients.

**Admin read.** Angular calls admin-api, which reads the replica for dashboards and the primary for anything it will immediately act on.

## Where the state lives

Postgres holds everything: identity graph, events, consent ledger, sends, journey runs, campaign recipients, config versions, audit log. There is deliberately no Redis and no Kafka in v1.

`FOR UPDATE SKIP LOCKED` is a genuinely good queue up to a few hundred thousand jobs a day, and one datastore means one backup story, one consistency model, and transactional guarantees across "record the send" and "book the spend". Introduce Kafka when the event volume justifies it — the ingest layer already writes through one function, so the swap is contained.

## What talks to what

| From | To | Protocol | Auth |
|---|---|---|---|
| Angular | admin-api | REST + JSON | JWT access token, 15 min |
| Shopify | ingest-api | webhook | HMAC-SHA256 over raw body |
| Meta | ingest-api | webhook | `X-Hub-Signature-256` |
| Courier | ingest-api | webhook | shared secret + IP allowlist |
| Storefront SDK | ingest-api | REST | signed app-proxy request |
| worker | Meta Cloud API | REST | system-user token |
| worker | FCM | gRPC/REST | service account |

## Sources

- [Micronaut Framework 5.0.0 Released](https://micronaut.io/2026/05/20/micronaut-framework-5-0-0-released/)
- [Micronaut Framework 5.0 with Java 25 baseline](https://micronaut.io/2026/04/27/micronaut-framework-5-0-with-java-25-baseline/)
