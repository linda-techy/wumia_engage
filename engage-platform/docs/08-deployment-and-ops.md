# 08 — Deployment and operations

## Build

```
./gradlew build                          # all modules, tests, OpenAPI spec
./gradlew :admin-api:dockerBuild         # JVM image
./gradlew :ingest-api:dockerBuildNative  # GraalVM native, optional
cd admin-ui && npm run build             # Angular, hashed assets
```

Micronaut's Gradle plugin builds the images; there is no hand-written Dockerfile to drift.

Native image is worth it for `ingest-api` if webhook cold starts ever matter — ~40ms start, ~40MB RSS. It costs a slower CI build and requires reflection registration for anything deserialised dynamically. The JVM is fine for `admin-api` and `worker`.

## Runtime shape

| Service | Replicas | CPU | Memory | Notes |
|---|---|---|---|---|
| `admin-api` | 2 | 0.5 | 512Mi | Low traffic, highest privilege |
| `ingest-api` | 3–10 | 0.5 | 512Mi | HPA on request rate; must absorb Shopify bursts |
| `worker` | 2–20 | 1 | 1Gi | HPA on queue depth, not CPU |
| Postgres | 1 + 1 replica | 4 | 16Gi | Managed (RDS/Cloud SQL) |

Worker autoscaling keys on pending work, not CPU. These pods are IO-bound on Postgres and provider HTTP, so CPU stays flat while a backlog grows — a CPU-based HPA will sit at 2 replicas while 400,000 campaign recipients wait.

```yaml
metrics:
  - type: External
    external:
      metric: { name: engage_pending_work }
      target: { type: AverageValue, averageValue: "5000" }
```

Exported from a single query:

```sql
SELECT (SELECT COUNT(*) FROM journey_runs
         WHERE status IN ('active','waiting') AND next_run_at <= now())
     + (SELECT COUNT(*) FROM campaign_recipients WHERE state = 'pending')
    AS pending_work;
```

## Migrations

Flyway, forward-only, run as a Kubernetes `Job` before rollout — never on application startup. Twenty worker pods racing to migrate the same database is a bad afternoon.

Every migration must be backward compatible with the currently-running version, because during a rollout both versions are live:

- Adding a column: safe. Add nullable, backfill, then make it `NOT NULL` in a later release.
- Dropping a column: three releases. Stop writing, deploy, then drop.
- Renaming anything: never. Add the new, dual-write, backfill, stop reading the old, drop it.

```
db/migration/
  V1__core.sql
  V2__admin_and_config.sql
  V3__campaign_rate_buckets.sql
```

Index creation on live tables uses `CREATE INDEX CONCURRENTLY`, which cannot run inside a transaction — mark those migrations `-- flyway:executeInTransaction=false`.

## Secrets

Never in the database, never in the admin UI, never in `application.yml`. Injected as environment variables from the platform secret manager.

```
DB_URL, DB_PASSWORD
WA_ACCESS_TOKEN, WA_APP_SECRET, WA_PHONE_NUMBER_ID
FIREBASE_SERVICE_ACCOUNT_B64
AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
MSG91_AUTH_KEY
JWT_PRIVATE_KEY, JWT_PUBLIC_KEY
CONFIG_ENCRYPTION_KEY          # wraps operators.mfa_secret_enc
```

The console shows whether a credential is configured and when it was last rotated. It cannot display or set the value. An admin UI that can reveal the Meta token is a credential exfiltration tool for anyone who phishes an operator.

Rotate the WhatsApp system-user token quarterly. It is non-expiring by default, which means it is valid forever once leaked.

## Observability

Micrometer to Prometheus, structured JSON logs, OpenTelemetry traces.

**Metrics that matter more than the usual four golden signals:**

```
engage_sends_total{channel,category,status}
engage_blocks_total{reason}
engage_spend_paise_total{channel,category}
engage_wa_quality_rating{phone_number}        # 2=HIGH 1=MEDIUM 0=LOW
engage_optouts_total{channel,source}
engage_journey_runs{journey,status}
engage_pending_work
engage_policy_decide_seconds                  # p99; it is on every send path
```

**Alerts, with the reasoning:**

| Alert | Threshold | Why |
|---|---|---|
| WhatsApp quality drop | any HIGH→MEDIUM | Page. A Low rating can cut your daily send limit by two orders of magnitude within 24h. |
| Opt-out rate | >2× 7-day baseline | Leading indicator of a quality drop, usually by a few days |
| Daily budget | >80% before 14:00 IST | Something is looping |
| Block rate | >40% of attempts in 1h | Misconfiguration or a broken consent path |
| Journey stalled | runs `active` >1h | Worker wedged or a step throwing |
| Send failure rate | >5% on any channel | Provider or credential problem |
| Policy p99 | >50ms | The choke point is becoming the bottleneck |

Correlate logs with a `request_id` propagated from ingest through journey to send, so a single customer complaint resolves to one trace.

## Backup and recovery

Managed Postgres with PITR, 30-day retention. Restore rehearsed quarterly — an untested backup is a hypothesis.

`audit_log`, `consents` and `config_versions` are additionally exported nightly to object storage with a compliance lifecycle policy. They are the tables that answer regulatory questions, and they are the ones you least want to discover are corrupt.

## DPDP obligations

- **Erasure.** `POST /customers/{id}/erase` cascades through `identity_keys`, `profiles`, `events`, `devices`, `sends`. Consent rows are retained in anonymised form — you must be able to prove a withdrawal was honoured, which is itself a legal basis for keeping that record.
- **Access.** Export of everything held about an identity, as JSON, via the same async export path.
- **Consent evidence.** Every `consents` row carries the exact copy displayed, the page, IP, user agent, and a flag proving the box was not pre-ticked.
- **Retention.** Events older than 400 days are purged nightly; inactive devices are deactivated at 180 days.

## Runbook: quality rating drops

1. **Halt all marketing** from the dashboard header. Utility and authentication keep flowing, so orders still confirm and OTPs still land.
2. Open `/reports/blocks` and `/reports/quality` — identify which template or campaign preceded the drop.
3. Pause the offending template. It auto-pauses at `LOW`, but do it manually at `MEDIUM` rather than waiting.
4. Check opt-outs in the preceding 72 hours by source. A spike localised to one template names the culprit.
5. Reduce `cap.whatsapp.marketing.1d` to 0 with an `effective_to` 48 hours out, so it restores itself.
6. Resume in stages: utility first, then marketing to engaged segments only.

The point of step 1 being a single button in the header is that at 2am nobody should be reasoning about which of six things to disable.

## Runbook: spend spike

1. Check `/reports/spend` by category. A utility spike is usually a courier webhook storm; a marketing spike is usually a journey loop.
2. Query the top journeys by sends in the last hour.
3. Disable the offending journey — instant, no deploy.
4. If it is a loop, look for a journey whose exit conditions do not cover a path. Steps re-entering because the exit event never arrives is the usual cause.
5. Reconcile against Meta's billing export before assuming your own numbers are right. `pricing.category` on the status webhook is what you were billed for and it differs from what you intended when Meta re-classifies.

## Load characteristics

Rough figures for a brand doing ~50,000 orders a month:

| Metric | Steady | Festive peak |
|---|---|---|
| Events/sec | 20 | 400 |
| Journey runs live | 40,000 | 250,000 |
| Sends/day | 60,000 | 500,000 |
| Postgres size | ~80GB @ 12 months | |

Postgres handles this on one primary. The first thing to break at 5–10× is `events`, which needs monthly partitioning — plan for it, but do not build it on day one.

Partition `sends` at the same time and by the same key. Both tables are append-heavy, time-queried, and retention-managed, which is exactly the partitioning sweet spot.
