# P3 — Policy engine + push sending · Implementation

**Weeks 4–5 · Owners: BE1 (policy, orchestrator), BE2 (FCM, worker) · Design:** [`technical/phase-3-policy-and-push-sending.md`](../technical/phase-3-policy-and-push-sending.md), [`04-backend-micronaut.md`](../04-backend-micronaut.md)

## Outcome

The one door exists: `MessageOrchestrator.dispatch()` → `PolicyEngine.decide()` → `MessageRouter` → `ChannelAdapter`. It is the only path to a provider, and the build fails if anything else reaches a channel. Four push intents run in production. Every decision, including blocks and deferrals, is a `sends` row with its reason and config snapshot.

**This is the phase not to rush.** A policy regression is invisible until Meta's quality rating drops. Budget time for the test list in T02.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P3-T01 | Extract `persistence` module | BE1 | 0.5 d | P1 done |
| P3-T02 | `policy` module: `PolicyEngine`, config snapshot, kill switches, holdouts | BE1 | 3 d | T01, V10 |
| P3-T03 | Template registry as code + lint | BE2 | 1.5 d | T01 |
| P3-T04 | `channels` module + `FcmAdapter` | BE2 | 2 d | T03 |
| P3-T05 | `orchestrator` module: `MessageRouter`, `MessageOrchestrator` | BE1 | 2 d | T02, T04 |
| P3-T06 | `worker` app + `EventDispatcher` | BE2 | 1.5 d | T05 |
| P3-T07 | Four push intents | BE1 + BE2 | 2 d | T06 |
| P3-T08 | Engagement beacons, attribution, ArchUnit | BE2 | 1 d | T05 |

---

### ☑ P3-T01 — Extract `persistence`

> **Done 2026-09-27.** The module already existed as `core-persistence` (package `in.brand.engage.persistence`, extracted with the admin-api work), so the classes moved there rather than into a new `persistence` module. `DeviceRepository` did not exist yet: P2's device SQL lived in `ingest-api`'s `SubscriberService` and was extracted into it, with a `deactivate(deviceId, reason)` for FCM pruning. `./gradlew build`: 95 tests before and after (core-domain 58, admin-api 19, ingest-api 18).

Move `Db`, `Sql`, `SqlFiles`, `IdentityResolver`, `EventWriter`, `ConsentWriter` and `DeviceRepository` from `ingest-api` into a new `persistence` module, package `in.brand.engage.persistence`. `ingest-api` depends on it. No behaviour change.

**Keep the data-access style.** Plain JDBC with SQL in resource files, as in P1. Do not introduce Micronaut Data for existing code; the design doc's `@JdbcRepository` snippets are illustrative. Every query that uses a Postgres enum casts explicitly (`CAST(? AS channel)`), and no query uses the jsonb `?` operator (use `jsonb_exists`).

**Done when:** `./gradlew build` is green with identical test counts before and after, and `ingest-api` contains no `db` package.

**Claude Code prompt**
> P3-T01. Create module `persistence` and move the listed classes into `in.brand.engage.persistence`, together with their SQL resources. Update imports. No behaviour change: test counts before and after must match. Do not introduce Micronaut Data.

---

### ☐ P3-T02 — `policy` module

**Migration `V9__dispatch_and_kill_switches.sql`**
```sql
-- Event dispatch claims by this column, not by an id cursor: ids are assigned
-- at INSERT but become visible at COMMIT, so a cursor skips late-committing rows.
ALTER TABLE events ADD COLUMN dispatched_at TIMESTAMPTZ;
CREATE INDEX events_undispatched_idx ON events (occurred_at) WHERE dispatched_at IS NULL;
-- Existing history must not be replayed into journeys on first start.
UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL;

-- Defaults live on the key, not in config_versions: config_versions.changed_by
-- must be a real operator, and a default is not a decision anyone made.
-- Effective value = config_current row if one exists, else config_keys.default_value.
ALTER TABLE config_keys ADD COLUMN default_value JSONB;
UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('quiet_hours',                          '{"from":"21:00","to":"09:00"}'::jsonb),
  ('cap.whatsapp.marketing.1d',            '1'),
  ('cap.whatsapp.marketing.7d',            '3'),
  ('cap.push.marketing.1d',                '3'),
  ('cap.email.marketing.7d',               '3'),
  ('budget.whatsapp.marketing.daily_paise','200000'),   -- ₹2,000: a cautious start; set per business before go-live
  ('rate.whatsapp.marketing_paise',        '86'),       -- ≈ ₹0.8631; verify against your rate card
  ('rate.whatsapp.utility_paise',          '12'),       -- ≈ ₹0.115
  ('holdout.global_pct',                   '5.0'),
  ('journey.enabled',                      'true'),
  ('approval.required_above_paise',        '1000000')   -- ₹10,000
) AS v(key, val) WHERE config_keys.key = v.key;

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('halt.channel',   'CHANNEL','BOOL','Halt channel',
  'Stops every send on this channel, utility included. Use during a provider incident.','GUARDED',1),
 ('halt.marketing', 'GLOBAL', 'BOOL','Halt all marketing',
  'Stops marketing on every channel. Utility continues.','GUARDED',2),
 ('halt.journey',   'JOURNEY','BOOL','Halt journey', NULL,'GUARDED',3),
 ('push.ttl_seconds.default','CHANNEL','INT','Push TTL default (s)', NULL,'SAFE',70),
 ('push.campaign_stale_days','CHANNEL','INT','Push: token stale after (days)',
  'Stale tokens are excluded from campaigns, kept for back-in-stock.','GUARDED',71)
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = 'false' WHERE key IN ('halt.channel','halt.marketing','halt.journey');
UPDATE config_keys SET default_value = '43200' WHERE key = 'push.ttl_seconds.default';   -- 12 h
UPDATE config_keys SET default_value = '30'    WHERE key = 'push.campaign_stale_days';

CREATE FUNCTION notify_config_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('config_changed', NEW.key);
  RETURN NEW;
END $$;
CREATE TRIGGER config_versions_notify AFTER INSERT ON config_versions
  FOR EACH ROW EXECUTE FUNCTION notify_config_changed();
```

**Kill switches are config keys with a special read path**
- Read **uncached**, straight from `config_current`, on every decision. A kill switch that takes a cache TTL to bite is not a kill switch.
- `ConfigResolver` also `LISTEN`s on `config_changed` (a trigger on `config_versions` calls `pg_notify`) to drop its cache for everything else within a second.
- Setting a halt needs no second approver even where the key's risk tier would normally require one. Stopping is always cheap to approve; restarting follows the normal tier.
- The `LISTEN` connection is dedicated (not from the pool) and reconnects with backoff; on reconnect it drops the whole cache, because notifications sent while it was down are lost.

**Files**
```
policy/src/main/java/in/brand/engage/policy/PolicyEngine.java
policy/src/main/java/in/brand/engage/policy/Decision.java            # sealed: Allow | Block | Defer
policy/src/main/java/in/brand/engage/policy/BlockReason.java
policy/src/main/java/in/brand/engage/policy/DecisionRequest.java
policy/src/main/java/in/brand/engage/policy/ConfigResolver.java      # snapshot + LISTEN
policy/src/main/java/in/brand/engage/policy/ConfigSnapshot.java
policy/src/main/java/in/brand/engage/policy/KillSwitch.java          # uncached
policy/src/main/java/in/brand/engage/policy/Subject.java             # one load per decision
policy/src/main/java/in/brand/engage/policy/SubjectLoader.java
policy/src/main/java/in/brand/engage/policy/Holdouts.java
policy/src/main/resources/sql/subject_load.sql
policy/src/test/java/in/brand/engage/policy/PolicyEngineTest.java
```

**`BlockReason`** = the list in `04-backend-micronaut.md` plus `NO_WHATSAPP_OPT_IN`, `HIGHER_PRIORITY_ACTIVE` (used from P5), `MARKETING_HALTED`, `WA_BACKOFF` (131049, from P4).

**Decision order:** exactly `04-backend-micronaut.md` §The policy engine, with the consent step replaced by phase-3 §1, and `halt.marketing` checked in step 1 once the template category is known (so do the template lookup before the marketing halt, but after the channel halt).

**Subject load:** one SQL round trip (`subject_load.sql`) returning consent_current rows, active suppressions, active devices with stale flag, `channel_capability`, `profiles.wa_window_until`, locale and delivery state. Target p99 < 5 ms on a warm cache.

**Holdouts:** assignment is deterministic, first 8 bytes of SHA-256(`experiment:identity_id`) as an unsigned number, `mod 10000 < pct × 100`, written to `holdouts` on first evaluation so the bucket never changes when the percentage changes later.

**Tests** (Postgres, `engage_test`; one test per line, named after the rule)
- Channel halted → `CHANNEL_HALTED`, utility included.
- Marketing halted → marketing `MARKETING_HALTED`, utility allowed.
- Halt written in one connection is seen by the next decision in another, with no wait.
- Unknown / paused / wrong-channel template.
- iOS-web-only identity → push `UNREACHABLE`.
- Push without push/marketing grant → `NO_MARKETING_CONSENT`.
- WhatsApp utility without any WhatsApp grant → `NO_WHATSAPP_OPT_IN`; with a transactional-only grant → allowed; marketing with transactional-only → `NO_MARKETING_CONSENT`.
- Withdrawn after granted → blocked (latest row wins).
- Quiet hours 21:00–09:00 IST: marketing at 22:40 IST → `Defer` until 09:00 IST **next day**; utility exempt; the boundary minute 21:00 is inside.
- Frequency cap counts across journeys and excludes `blocked`/`deferred` rows.
- Budget: spent + unit > budget → block; budget 0 means unlimited.
- A key with no `config_versions` row resolves to `config_keys.default_value`; a key with neither fails `ConfigResolver` startup.
- Holdout control → `HOLDOUT_CONTROL`; the bucket is stable across two calls and a pct change.
- Every decision carries the config snapshot id.

**Done when:** all tests above pass and `PolicyEngineTest` runs in under 30 seconds.

**Claude Code prompt**
> P3-T02. Add V10 as specified. Build the `policy` module: `PolicyEngine.decide()` in the order given by `docs/04-backend-micronaut.md`, with the consent step from `docs/technical/phase-3-policy-and-push-sending.md` §1. Kill switches read uncached. `decide()` has no side effects except the holdout assignment row. Write every listed test first, against Postgres. Money is `long` paise; quiet hours are evaluated in IST.

---

### ☐ P3-T03 — Templates as code

**Files**
```
templates/push/back_in_stock.yaml, price_drop.yaml, browse_abandon.yaml, cart_recovery_1.yaml
templates/lint/banned_words.txt                                  # sale, offer, % off, shop now, limited time, flat ₹ ...
orchestrator/src/main/java/in/brand/engage/orchestrator/templates/TemplateRegistry.java
orchestrator/src/main/java/in/brand/engage/orchestrator/templates/TemplateLinter.java
orchestrator/src/main/java/in/brand/engage/orchestrator/templates/Renderer.java
```

```yaml
# templates/push/back_in_stock.yaml
key: push_back_in_stock_v1
channel: push
category: utility          # the shopper asked for it
cooldown: PT6H
locales:
  en:     { title: "Size {{size}} is back: {{product}}", body: "Only a few pieces in this restock." }
  hi-Latn: { title: "Size {{size}} wapas aa gaya: {{product}}", body: "Is restock mein kam pieces hain." }
vars: [size, product, url, image]
```

**Lint rules** (fail the build): utility/authentication containing a banned word; push title > 40 or body > 90 characters after rendering with the longest sample values; a variable used but not declared, or declared but not used; missing `en` locale. The registry upserts `templates` rows on worker startup.

**Done when:** `./gradlew :orchestrator:test` includes a lint test that fails on a fixture template with "flat ₹200 off" marked utility.

---

### ☐ P3-T04 — `channels` + `FcmAdapter`

**Files**
```
channels/src/main/java/in/brand/engage/channels/ChannelAdapter.java
channels/src/main/java/in/brand/engage/channels/RenderedMessage.java
channels/src/main/java/in/brand/engage/channels/DispatchResult.java
channels/src/main/java/in/brand/engage/channels/ChannelException.java    # Permanent / Transient
channels/src/main/java/in/brand/engage/channels/push/FcmAdapter.java
channels/src/main/java/in/brand/engage/channels/push/FcmTokenPruner.java
```

**Rules** (phase-3 §2)
- Firebase Admin SDK, `sendEachForMulticast`, max 500 tokens per call. Data-only messages. `webpush.headers`: `TTL`, `Urgency`, `Topic` (= tag).
- Data payload ≤ 4,000 bytes UTF-8; over that is a `PermanentChannelException` before calling FCM.
- Per-token result handling from the phase-2 §9 table. `INVALID_ARGUMENT` prunes only when the error names the token.
- `DispatchResult.delivered` = number of tokens FCM accepted. Zero accepted is a failure, not a send.
- `channels` must not import `policy` or `orchestrator`. The adapter receives a `RenderedMessage` and addresses; it has no idea why it is sending.
- Credentials from `FIREBASE_SERVICE_ACCOUNT_FILE`. A missing file fails startup of the worker, not of ingest.

**Tests:** a fake `FirebaseMessaging` returning mixed per-token results → correct devices deactivated with the right reasons; 4,001-byte payload rejected; 1,200 tokens → three calls.

**Done when:** tests pass, and a manual send from a test harness reaches a dev device.

---

### ☐ P3-T05 — Router and orchestrator

**Files**
```
orchestrator/src/main/java/in/brand/engage/orchestrator/MessageOrchestrator.java   # interface, CLAUDE.md §3
orchestrator/src/main/java/in/brand/engage/orchestrator/DefaultOrchestrator.java
orchestrator/src/main/java/in/brand/engage/orchestrator/MessageRouter.java
orchestrator/src/main/java/in/brand/engage/orchestrator/MessageIntent.java
orchestrator/src/main/java/in/brand/engage/orchestrator/SendCommand.java
orchestrator/src/main/java/in/brand/engage/orchestrator/SendResult.java            # sealed
orchestrator/src/main/java/in/brand/engage/orchestrator/SendRepository.java
```

**The transaction boundary differs from the design snippet on purpose.** Holding a database transaction open across an HTTP call to FCM or Meta pins a connection for the provider's latency and, under a slow provider, exhausts the pool. So:

1. **Tx 1:** idempotency check, `decide()`, insert the `sends` row (`blocked`, `deferred` or `queued`). Commit.
2. **No tx:** call the adapter.
3. **Tx 2:** `markSent` / `markFailed`, book spend (non-WhatsApp), prune tokens.

A crash between 1 and 3 leaves a `queued` row. A sweeper marks `queued` rows older than 10 minutes as `failed` with reason `lost_in_flight`; it never re-sends them, because the provider may have delivered.

**Idempotency:** `sends.idempotency_key` is UNIQUE. A deferral writes a row with a key suffixed `#defer:<n>` so the real key stays free for the retry (invariant 5).

**P3 scope of the orchestrator:** `dispatch(MessageIntent)` creates a `cascade_runs` row and runs step 0 synchronously if it is due. Single-step cascades only; the multi-step runner comes in P4-T06.

**Tests:** duplicate key → `duplicate` with no second row; block and defer persisted with reason and snapshot id; adapter throws transient → `failed`, retriable; permanent → suppression row; the sweeper; two concurrent `dispatch` calls for the same intent and subject → one live `cascade_runs` row (unique index).

**Done when:** tests pass and `SELECT status, count(*) FROM sends GROUP BY 1` after the suite shows every status the tests expect.

**Claude Code prompt**
> P3-T05. Build `MessageRouter` and `MessageOrchestrator` as specified, with the three-step transaction boundary (the provider call is outside any transaction). Blocked and deferred decisions are persisted (invariant 3); a deferral does not consume the idempotency key (invariant 5). Only the router may hold `ChannelAdapter` references.

---

### ☐ P3-T06 — `worker` app + `EventDispatcher`

**Files**
```
worker/build.gradle.kts                                           # Micronaut application
worker/src/main/java/in/brand/engage/worker/Application.java
worker/src/main/java/in/brand/engage/worker/EventDispatcher.java
worker/src/main/java/in/brand/engage/worker/EventConsumer.java    # interface: accepts(name), handle(event)
worker/src/main/java/in/brand/engage/worker/QueuedSweeper.java
```

**Claim loop** (every 1 s):
```sql
WITH due AS (
  SELECT id FROM events
   WHERE dispatched_at IS NULL
   ORDER BY occurred_at
   LIMIT 200
   FOR UPDATE SKIP LOCKED
)
UPDATE events e SET dispatched_at = now()
  FROM due WHERE e.id = due.id
RETURNING e.*;
```
Consumers run in the same transaction as the claim; if one throws, the transaction rolls back and the event is reclaimed. Consumers must therefore be idempotent (they are: `dispatch()` dedupes on the live cascade index). The dispatcher counts failures per event id in memory; after 5 it claims the event in a separate transaction without running consumers, and logs it at ERROR with the exception (alert on that log line). One poison event must not stall the stream.

**Done when:** a test inserts events in three concurrent transactions committing out of id order and every event is dispatched exactly once.

---

### ☐ P3-T07 — Four push intents

Per phase-3 §4. Each is a `EventConsumer` that builds a `MessageIntent`. None calls the router.

| Intent | Consumes | Notes |
|---|---|---|
| `back_in_stock` | `variant_restocked` (P1-T03) | waitlist rows for the variant, fan-out ≤ quantity × 20, oldest first; stale tokens allowed; set `stock_waitlist.notified_at` |
| `price_drop` | `price_dropped` (P1-T04) | open carts + waitlists holding the variant; real numbers in copy via `Paise.toRupeeString` |
| `browse_abandon` | `product_viewed` (pixel, P2-T06) | ≥ 3 views, no add-to-cart, 30 min idle; delay 4 h. Identity via anon key only if already linked; otherwise skip |
| `cart_recovery` step 1 | `cart_updated` | 45 min delay; restarts on each cart change (cancel the live run, start a new one); exits on `order_placed` for that cart |

**Done when:** each intent has a test from event → `sends` row (sent, or blocked with the expected reason), and one real restock on the dev store delivers a push to a waitlisted test device.

---

### ☐ P3-T08 — Beacons, attribution, ArchUnit

- `/engagement` (P2-T01) now sets `sends.clicked_at` (first click only) and `devices.last_clicked_at`, and calls `orchestrator.onSignal(identityId, subjectKey, CLICKED)`.
- Every push URL gets `utm_source=engage&utm_medium=push&utm_campaign=<intent>&utm_content=<sid>` in the renderer, not in templates.
- `ArchitectureTest` in `worker` (it depends on every module) with the two rules from phase-3 §6, plus: `ingest-api` must not depend on `channels`.

**Done when:** the ArchUnit test fails on a deliberately added import of `FcmAdapter` in a journey class, and passes once it is removed.

---

## Exit gate

- [ ] All phase-3 acceptance criteria ticked
- [ ] `back_in_stock` live in production for 7 days; click rate measured from `sends.clicked_at`
- [ ] A kill-switch drill: halt push in production, confirm the next send blocks with `CHANNEL_HALTED`, un-halt
- [ ] Zero `lost_in_flight` rows over the 7 days
