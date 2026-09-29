# P3 — Policy engine + push sending · Implementation

**Weeks 4–5 · Owners: BE1 (policy, orchestrator), BE2 (FCM, worker) · Design:** [`technical/phase-3-policy-and-push-sending.md`](../technical/phase-3-policy-and-push-sending.md), [`04-backend-micronaut.md`](../04-backend-micronaut.md)

## Outcome

The one door exists: `MessageOrchestrator.dispatch()` → `PolicyEngine.decide()` → `MessageRouter` → `ChannelAdapter`. It is the only path to a provider, and the build fails if anything else reaches a channel. Four push intents run in production. Every decision, including blocks and deferrals, is a `sends` row with its reason and config snapshot.

**This is the phase not to rush.** A policy regression is invisible until Meta's quality rating drops. Budget time for the test list in T02.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P3-T01 | Extract `persistence` module | BE1 | 0.5 d | P1 done |
| P3-T02 | `policy` module: `PolicyEngine`, config snapshot, kill switches, holdouts | BE1 | 3 d | T01, V8 |
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

### ☑ P3-T02 — `policy` module

> **Done 2026-09-27.** The migration is **V8**, not V10: V8 and V9 (P1 gaps, P2 views) had not been built, and Flyway refuses a lower version once a database has a higher one, so this took the next free number and those moved up (README, Migrations). `PolicyEngineTest`: 31 tests in 1.9 s on `wumika_admin_test`; `./gradlew build` 126 tests (95 + 31); `invariants.sql` 17/17 on a fresh V1–V8 database. A mutation check (halt read disabled, quiet-hours boundary made exclusive, LISTEN no longer invalidating) failed exactly the four tests for those rules.
>
> Where the build differs from the text below:
> - **Budget exhaustion defers to the next IST midnight** (`Defer`, `DAILY_BUDGET_EXHAUSTED`) instead of blocking. CLAUDE.md invariant 5 names budget exhaustion as a deferral, and CLAUDE.md wins.
> - **`holdout.journey_pct`** (JOURNEY, DECIMAL, CRITICAL, default 0) is added in V8: the journey holdout needs a percentage and no key existed. 0 means no holdout runs and nothing is written; P5-T09 sets it per journey.
> - **The global holdout applies to every marketing send**, not only journey sends: campaigns must not message it either (05-campaigns.md).
> - **Template cooldown (step 8) is not enforced yet**: the `templates` table has no cooldown; the P3-T03 registry carries it.
> - `Channel` and `Category` live in `core-domain` (`in.brand.engage.core.messaging`), so `channels` can use them without importing `policy`.
> - Extra classes beyond the file list: `Template`, `Templates` (uncached lookup, so a pause bites on the next send), `Addresses`, `Usage` (cap counts, spend), `PolicyClock`. SQL: `halts.sql`, `config_snapshot.sql`.
> - The LISTEN connection is opened with `datasources.default.url/username/password`; with no URL it logs a warning and relies on the 30 s cache expiry.

**Migration `V8__dispatch_and_kill_switches.sql`** (as specified, plus `holdout.journey_pct`)
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
> P3-T02. Add V8 as specified. Build the `policy` module: `PolicyEngine.decide()` in the order given by `docs/04-backend-micronaut.md`, with the consent step from `docs/technical/phase-3-policy-and-push-sending.md` §1. Kill switches read uncached. `decide()` has no side effects except the holdout assignment row. Write every listed test first, against Postgres. Money is `long` paise; quiet hours are evaluated in IST.

---

### ☑ P3-T03 — Templates as code

> **Done 2026-09-28.** New `orchestrator` module (templates package only; the router is T05). `TemplateLinterTest` includes `a_utility_template_saying_flat_200_off_fails` on `fixtures/utility_with_offer.yaml`, and the registry refuses to load a template that fails the lint. `:orchestrator:test` 24 tests; `./gradlew build` 152 tests.
>
> Beyond the text below:
> - **`samples:`** in each YAML: the longest realistic value of every copy variable. The length rule renders with these; at send time the renderer cuts a longer title/body with "…" so Android never truncates mid-word. Lengths count code points, not UTF-16 units.
> - **`banned_words.txt` holds regular expressions**, one per line, so `\d{1,3}\s?%\s?off` and `flat\s?₹\d` can be expressed; it covers the 05-campaigns.md markers plus coupon/promo/deal/hurry/buy now/chhoot.
> - Push `url` and `image` are payload variables: declared but not used in copy is fine for them, and push must declare `url`. Unknown YAML fields fail (a typo like `cooldwon` would otherwise silently drop the cooldown).
> - **Template cooldown is now enforced by policy (step 8)**: policy defines `TemplateCooldowns`, the registry implements it, and a `@Secondary` no-cooldown default keeps `policy` usable without `orchestrator`. Two policy tests added.
> - Registry sync upserts key/channel/category and never touches `status`, so an operator's pause survives a deploy. Templates ship on the classpath with a generated `templates/index.txt` (a jar cannot be listed).
> - Hinglish back-in-stock title is "Size {{size}} aa gaya: {{product}}" (the spec's "wapas aa gaya" leaves too little room for a product name at 40 characters). Cooldowns for the three new templates (price drop and browse abandon 1 day, cart recovery 12 h) are starting values; tune them in the YAML.

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

### ☑ P3-T04 — `channels` + `FcmAdapter`

> **Done 2026-09-29.** `FcmAdapterTest` (13) and `FcmTokenPrunerTest` (1, Postgres) pass; `./gradlew build` 167 tests (1 skipped: the smoke test). **Manual send:** `FcmSmokeTest` sent one push through `FcmAdapter` → `FirebaseFcmClient` to the dev-store subscriber (device 1 on the dev server, Chrome on Android) at 00:41 IST; FCM accepted it and it was displayed on the phone. Rerun with `FCM_SMOKE_TOKEN=<token> ./gradlew :channels:test --tests '*FcmSmokeTest'`.
>
> **Two things broke the first attempts, and one is a real dependency fix:**
> - **firebase-admin 9.4.3 cannot send under Micronaut 5.** It asks for httpclient5 5.3.1; Micronaut's platform lifts it to 5.6.1, which decompresses responses itself, so every call failed `ZipException: Not in GZIP format`. Upgraded to **9.11.0**, built against httpclient5 5.6.
> - **Avast Web/Mail Shield on the developer PC** intercepts HTTPS (also inside Docker Desktop): PKIX failures for Maven and Firebase under the JDK trust store. With Avast off (or `-Djavax.net.ssl.trustStoreType=Windows-ROOT`) this is not a problem.
>
> **Chrome on Android labelled the test push "possible spam".** Chrome runs an on-device check on web push and warns on notifications that look deceptive. The test push invited it: the title said "Engage", not the brand; the body read like a system test; there was no icon; it came from a `myshopify.com` origin just after midnight. What this means for real sends, before P3-T07 goes live:
> - Every push shows the brand: the service worker should default `icon` and `badge` to the Wumika icon (today `sw.js` sends none unless the payload has one), and copy leads with the product, as the templates already do.
> - Send from the production domain the shopper subscribed on, not `*.myshopify.com`, once the storefront runs there.
> - Never send test-style copy to a real subscriber. The smoke test's copy was for this check only.
> - Measure it: if real sends are flagged too, the P2 metrics (permission revocations per surface) will show it as rising unsubscribes.
>
> Where the build differs from the text below:
> - **`send(RenderedMessage, Addresses)`**, not `send(Decision.Allow, …)`: `Decision` is policy's type and `channels` may not import it. `Addresses` moved from `policy` to `core-domain` so both share it.
> - **The adapter never writes to the database.** Dead tokens come back as `TokenPrune`s on the result, or on the exception when nothing was accepted, and the router applies them with `FcmTokenPruner` in its post-send transaction (T05 step 3). That keeps the adapter call outside any transaction.
> - **`FcmClient`** wraps the SDK because its `BatchResponse`/`SendResponse` cannot be built outside it; tests fake `FcmClient`. `FirebaseFcmClient` is the real one.
> - **`ChannelException.Permanent` carries `suppress`.** Payload too large, no live tokens and all tokens dead are permanent but never suppress the customer. Zero accepted with any transient error is `Transient`.
> - **Web Push `Topic`** allows ≤ 32 URL-safe base64 characters, so a tag like `restock:4471` is sent as a stable 32-character hash of itself.
> - If a later 500-token chunk fails as a whole after an earlier one was accepted, the result reports what was accepted rather than a failure.
> - `FcmClientFactory` is excluded in the `test` environment. Making the worker fail at boot on a missing file (not at the first send) is P3-T06: resolve the adapter eagerly there. A relative `FIREBASE_SERVICE_ACCOUNT_FILE` resolves against the working directory, so `:worker:run` should run from the repo root.

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
  - **Already provisioned (2026-09-27), project `wumika-75a48`, verified with an FCM `validate_only` call:** locally `config/firebase-service-account.json` (git-ignored; `local.env` and `devstore.env` already point at it); on the dev server `/opt/wumika/secrets/firebase-service-account.json` (mode 400, uid 10001 = the container user). The worker's compose service mounts `./secrets:/secrets:ro` and sets `FIREBASE_SERVICE_ACCOUNT_FILE=/secrets/firebase-service-account.json` (add both in P3-T06).

**Tests:** a fake `FirebaseMessaging` returning mixed per-token results → correct devices deactivated with the right reasons; 4,001-byte payload rejected; 1,200 tokens → three calls.

**Done when:** tests pass, and a manual send from a test harness reaches a dev device.

---

### ☑ P3-T05 — Router and orchestrator

> **Done 2026-09-29.** `MessageRouterTest` (12) and `DefaultOrchestratorTest` (9) pass against Postgres with a fake push adapter; `./gradlew build` 188 tests (1 skipped: the FCM smoke test). After the suite, `SELECT status, count(*) FROM sends GROUP BY 1` on `wumika_admin_test` showed blocked 6, deferred 9, queued 2 (the sweeper test's in-flight row, one per run), sent 20, failed 15 (including `lost_in_flight`), clicked 1 (a policy fixture). Mutation check: a deferral taking the real key, and suppressing on every permanent failure, each failed their tests. The one-live-run guarantee is V4's partial unique index; 8 concurrent dispatches return one run.
>
> Where the build differs from the text below:
> - **`DefaultOrchestrator.tick(limit)`** runs steps that came due later: deferrals and delayed intents. Without it a quiet-hours deferral in P3 would never be retried (invariant 5). P3-T06 schedules it next to the sweeper. Steps are claimed with a 5-minute lease so `dispatch` and `tick` never run one twice; the per-step key `run:<id>:step:<n>` backs that up.
> - **Deferral cap:** a step is rescheduled at most 3 times; the 4th `Defer` skips it (`deferred_limit:<reason>`).
> - **Run outcomes** in P3 (single step): sent → `exhausted/sent`; blocked → `exhausted/blocked:<reason>`; failed → `failed/failed:<code>`, not retried (retry with backoff is the P4-T06 runner). T08's `onSignal(CLICKED)` should move `exhausted/sent` to `succeeded`.
> - **A suppression is added only when `ChannelException.Permanent.suppress()` is true** (the provider blamed the recipient), not on every permanent failure: an oversized payload or a render bug must never suppress a customer. Render failures and a template missing from the registry fail the send without calling the provider.
> - **`MessageIntent.vars` is `Map<String,String>`** (rendered text), not `Map<String,Object>`; it round-trips through `cascade_runs.vars` with `jsonb_object` / `jsonb_each_text`, so no JSON library is needed.
> - **`Priority`** has the four phase-5 §5 levels (matches `cascade_runs.priority` 1–4), not CLAUDE.md §3.1's three.
> - **One live run per (intent, subject) across all people** (V4 index). A subject shared by many people must include the identity: T07's `back_in_stock` uses `<variantId>:<identityId>`, or the second waitlisted shopper is deduped away.
> - `sends.created_at` comes from the injected clock, like the policy's cap windows; `Decision.Allow` now carries the person's `locale` so the router renders Hinglish where set.
> - The sweeper is `SendRepository.sweepLostInFlight()`; T06's `QueuedSweeper` schedules it.

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

### ☑ P3-T06 — `worker` app + `EventDispatcher`

> **Done 2026-09-29.** `EventDispatcherTest` (4): 60 events inserted by three concurrent transactions, interleaved so the first takes the lowest ids and commits last, with two dispatchers racing throughout: each event reached its consumer exactly once. With `FOR UPDATE SKIP LOCKED` removed the same test fails (an event handled twice). `./gradlew build` 192 tests (1 skipped: the FCM smoke test). Boot checks: the worker starts against a V8 database with the real Firebase client (`worker ready: channels [PUSH]`, 4 templates, `/health` UP); with a missing `FIREBASE_SERVICE_ACCOUNT_FILE` it exits at boot naming the variable; against a database without V8 it exits at boot (`ConfigResolver`).
>
> Where the build differs from the text below:
> - **Consumers return intents; they do not call `dispatch()`.** `dispatch()` commits its own transactions and calls the provider at once, so a claim transaction that rolled back afterwards would reclaim the event and re-send a single-step run that had already finished (the live-run dedupe only covers live runs). Instead `EventConsumer.handle(Connection, Event)` returns `MessageIntent`s; the dispatcher creates their runs with `DefaultOrchestrator.enqueue(c, intent)` on the claim's connection, so event, consumer writes and runs commit together, exactly once; steps run with `runIfDue` only after the commit.
> - **One savepoint per event**, not one transaction per batch: a throwing consumer rolls back only its event, the rest of the batch commits. After 5 failures the event is marked dispatched without consumers and logged at ERROR.
> - **Scheduled jobs** (`WorkerJobs`, `QueuedSweeper`): event dispatch every 1 s (drains full batches), the cascade `tick` every 5 s, the sweeper every minute. `WORKER_JOBS_ENABLED=false` turns them off; tests drive them by hand.
> - **`WorkerStartup`** builds the router, and so every channel adapter, at boot; that is what makes a bad credentials file fail the start rather than the first push.
> - **Deploy:** `deploy/docker-compose.yml` has a `worker` service with `./secrets:/secrets:ro` and `FIREBASE_SERVICE_ACCOUNT_FILE=/secrets/firebase-service-account.json`, no published port; `.github/workflows/deploy-dev.yml` builds and ships `wumika/worker`. **Merging to `dev` starts the worker on the dev server.** With no consumers until P3-T07 it only marks new events dispatched; events it marks are not replayed when T07's consumers arrive.
> - On Windows, `bin/worker.bat` fails ("The input line is too long"); run `./gradlew :worker:run` or `java -cp "worker/build/install/worker/lib/*" in.brand.engage.worker.Application`. The Docker image uses the Unix script.

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

### ◐ P3-T07 — Four push intents

> **2026-09-30: `cart_recovery`, `back_in_stock` and `price_drop` built; `browse_abandon` waits for `product_viewed` (P2-T06).**
>
> **`price_drop`** (`worker/.../intents/PriceDrop.java`): `PriceDropTest` (7) goes from `price_dropped` (P1-T04) to sends. Reaches identities with the variant in an open (unconverted) cart or on the waitlist, once per person even when both; anonymous carts reach nobody. The waitlist is only read: `notified_at` stays for the restock alert. Copy uses the event's real prices, `₹` + `Paise.toRupeeString` ("Now ₹1,499, was ₹1,899"); TTL 24 h, normal urgency, `MID_INTENT`; subject `<variant>:<identity>`; link to the product page with the variant selected. **The one-day template cooldown is kept on purpose:** unlike back-in-stock (which the shopper asked for), a store-wide markdown would otherwise send one push per item; the test pins that a second drop the same day is held back. No fan-out cap: the audience is people who already chose the item.
>
> **`back_in_stock`** (`worker/.../intents/BackInStock.java`): `BackInStockTest` (6) goes from `variant_restocked` to sends. At most quantity × 20 shoppers, oldest waitlist entry first, marked `notified_at` in the transaction that starts their intents (removing the cap or the marking fails the tests). **No template cooldown** (removed 2026-09-30 after a dev-store test): every one-size product shares one template, so a per-template cooldown hid restocks of other items the shopper was waiting for. The waitlist is the dedupe, and tapping "notify me" again after an alert re-arms the entry. TTL 1 h, urgency high, stale tokens allowed; subject `<variant>:<identity>`. The push names the product and size: the event now carries `product_title`, `product_handle` and `variant_title` (P1-T03 fetches them with the variant lookup), the size is the waitlist's `size_label` else Shopify's variant title, and a one-size product ("Default Title") gets `push_back_in_stock_nosize_v1` ("Back in stock: {{product}}"). A cascade step may list alternate templates, picked by the reserved `template` variable; anything not listed is ignored. The link is `<storefront>/products/<handle>?variant=<id>`. **Dev-store check 2026-09-29:** a real 0 → 1 restock of a waitlisted item produced `variant_restocked`, a cascade run and a push FCM accepted, about a second after Shopify's webhook. The "notify me" button is the new app block `Engage notify me` (P2), which shows only on a sold-out variant.
>
> **`cart_recovery`** (`worker/.../intents/CartRecovery.java`): `CartRecoveryTest` (8) goes from a `cart_updated` event to a `sends` row, sent or blocked `NO_MARKETING_CONSENT`. Every cart change cancels the live run (`cart_changed`) and starts a new one 45 min after the change; an emptied cart ends it (`cart_emptied`), and so does `order_placed` for the cart or a cart already converted. Anonymous carts start nothing. Removing the restart or the order cancel fails those tests. `./gradlew build` 200 tests.
> - **The template lost its scarcity line.** `push_cart_recovery_v1` was "Your {{size}} is still in your bag" / "only {{left}} left in your size", but cart lines carry no stock level (inventory arrives with P1-T03) and no clean size (variant titles can be "Default Title" or "M / Blue"). Scarcity copy is only allowed with a true count, so v1 is now "Still in your bag: {{product}}" / "It's saved for you. Complete your order when you're ready." A stock-aware version follows P1-T03. v1 had never been sent.
> - The push names the most expensive item in the cart and links to `<STOREFRONT_BASE_URL>/cart` (falls back to `https://SHOPIFY_SHOP_DOMAIN/cart`). Set `STOREFRONT_BASE_URL` to the domain shoppers subscribe on before this goes live (see T04's spam finding).
> - The "Done when" restock-to-device check belongs to `back_in_stock` and waits for P1-T03.

Per phase-3 §4. Each is a `EventConsumer` that builds a `MessageIntent`. None calls the router.

| Intent | Consumes | Notes |
|---|---|---|
| `back_in_stock` | `variant_restocked` (P1-T03) | waitlist rows for the variant, fan-out ≤ quantity × 20, oldest first; stale tokens allowed; set `stock_waitlist.notified_at` |
| `price_drop` | `price_dropped` (P1-T04) | open carts + waitlists holding the variant; real numbers in copy via `Paise.toRupeeString` |
| `browse_abandon` | `product_viewed` (pixel, P2-T06) | ≥ 3 views, no add-to-cart, 30 min idle; delay 4 h. Identity via anon key only if already linked; otherwise skip |
| `cart_recovery` step 1 | `cart_updated` | 45 min delay; restarts on each cart change (cancel the live run, start a new one); exits on `order_placed` for that cart |

**Done when:** each intent has a test from event → `sends` row (sent, or blocked with the expected reason), and one real restock on the dev store delivers a push to a waitlisted test device.

---

### ☑ P3-T08 — Beacons, attribution, ArchUnit

> **Done 2026-09-29.** Done-when check: with a `FcmAdapter` field added to `CartRecovery` (a journey class), `ArchitectureTest` fails on `only_the_router_reaches_channels` and `only_the_router_holds_channel_adapters`, naming the field; with it removed, all 5 rules pass. `./gradlew build` 220 tests (1 skipped: the FCM smoke test).
>
> Where the build differs from the text below:
> - **Beacons are signed.** Send ids are sequential, so an unsigned click beacon would let anyone mark other people's pushes clicked and end their cascades. The router puts `sig` = HMAC of the send id into every push (`core-domain` `BeaconSignature`, keyed from `SHOPIFY_API_SECRET`, which ingest-api and the worker both already have: nothing new to provision). The service worker echoes it; ingest-api records only beacons whose signature matches, and answers 204 either way. The worker now needs `SHOPIFY_API_SECRET` to start.
> - **ingest-api does not call the orchestrator.** A signed click sets `sends.clicked_at` (first click only) and `status = clicked`, and writes a `push_clicked` event; the worker's `PushClicks` consumer turns it into `DefaultOrchestrator.onSignal(runId, "clicked")`, which marks the run `succeeded` (also a single-step run that already finished). An impression sets `delivered_at` and `status = delivered`.
> - **`devices.last_clicked_at` is not set:** a multicast sends one payload to all of a person's devices, so the beacon cannot say which device was clicked. The 180-day dormant rule (phase-2 §9) therefore relies on refreshes alone.
> - **ArchUnit rules** (`worker` `ArchitectureTest`): only orchestrator and channels touch `channels`; only `MessageRouter` references a `ChannelAdapter` (Micronaut's generated bean definitions excepted); `channels` never depends on policy, orchestrator or worker; policy never depends on orchestrator, channels or worker; `worker.intents` never touches `MessageRouter` or `SendRepository`. `ingest-api`'s own `IngestArchitectureTest` forbids channels, orchestrator and worker. ArchUnit reads bytecode: an unused import is invisible to it, a reference is not.
> - UTMs are applied by the router (`Utm`), keeping an existing query string and putting the fragment last. The `sw.js` change reaches browsers when ingest-api is deployed (served `no-cache`).

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
