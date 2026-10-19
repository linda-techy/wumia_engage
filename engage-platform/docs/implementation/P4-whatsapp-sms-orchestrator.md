# P4 — WhatsApp, SMS, cascades · Implementation

**Weeks 5–7 · Owners: BE1 (WhatsApp, capability), BE2 (SMS, cascade runner) · Design:** [`technical/phase-4-whatsapp-sms-orchestrator.md`](../technical/phase-4-whatsapp-sms-orchestrator.md), `CLAUDE.md` §3–5

**Status (2026-10-10):** ☑ done · ◐ built and tested against fakes; the live check in "Done when" waits for Meta verification, template approval or DLT.

**Blocked until:** P0-T01 (Meta verified, number approved), P0-T02 (utility templates approved **as UTILITY**), P0-T03 (DLT header and templates). T01, T05 and T07 can be built and tested against fakes before the approvals land; nothing is sent to a real customer until they have.

## Outcome

Order updates reach opted-in customers on WhatsApp, fall back to DLT SMS when WhatsApp cannot deliver, and teach the system which numbers have WhatsApp, all for utility prices. Multi-step cascades run on the worker. STOP in English, Hinglish or Hindi withdraws consent everywhere it should.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P4-T01 | Meta webhook intake | BE1 | 1 d | P3 done |
| P4-T02 | `WhatsAppCloudAdapter` + error classification | BE1 | 1.5 d | T01 |
| P4-T03 | Template sync + number-health migration | BE1 | 1.5 d | T02 |
| P4-T04 | Status and inbound handling: spend, service window, STOP | BE1 | 2 d | T01, T02 |
| P4-T05 | `CapabilityService` | BE1 | 1.5 d | T04 |
| P4-T06 | `Msg91Adapter` + DLT registry + delivery reports | BE2 | 2 d | P3 done |
| P4-T07 | Multi-step cascade runner + `onSignal` | BE2 | 2.5 d | P3 done |
| P4-T08 | `order_tracking` journey | BE1 + BE2 | 1.5 d | T02–T07, P1-T02 |

---

### ◐ P4-T01 — Meta webhook intake

> **Built 2026-10-09** (`152fa82`). `GET/POST /meta/webhook` as specified. Live check pending: the Meta dashboard "Test" button needs the webhook URL configured on the app.
>
> Where the build differs from the text below:
> - **Customer allowlist by phone** (`InboxRepository.storeMeta`): a payload is stored only if every `wa_id` / `from` / `recipient_id` is the phone key of an identity that also holds an allowlisted email. Payloads with no customer numbers (template status, number quality) are always stored.
> - The verifier lives at `core-domain/.../core/meta/MetaSignatureVerifier.java`.

**Files**
```
core-domain/src/main/java/in/brand/engage/meta/MetaSignatureVerifier.java      # X-Hub-Signature-256: "sha256=" + hex HMAC(app secret, raw body)
core-domain/src/test/java/in/brand/engage/meta/MetaSignatureVerifierTest.java
ingest-api/src/main/java/in/brand/engage/ingest/web/MetaWebhookController.java
ingest-api/src/main/java/in/brand/engage/ingest/meta/MetaInboxHandler.java
ingest-api/src/test/resources/fixtures/meta_status_delivered.json, meta_status_failed_131026.json,
                                       meta_inbound_text.json, meta_inbound_stop_hinglish.json,
                                       meta_template_status.json
```

- `GET /meta/webhook`: `hub.mode=subscribe` and `hub.verify_token == WA_WEBHOOK_VERIFY_TOKEN` → echo `hub.challenge` as `text/plain`; otherwise 403.
- `POST /meta/webhook`: `@Body byte[]`, verify with `WA_APP_SECRET` (the **app** secret, not the access token), then write to `webhook_inbox`. One Meta POST can carry several entries and changes, so the **delivery id** is the SHA-256 of the raw body; the handler fans out and dedupes each status and message by its own id (`wamid` + status) in the tables it writes.
- 401 on a bad signature, 200 on everything else that was stored.

**Tests:** vector computed independently; `sha256=` prefix required; wrong secret → 401; the same body twice → one inbox row.

**Done when:** tests pass and the Meta dashboard's "Test" button for the `messages` field lands a row in `webhook_inbox`.

---

### ◐ P4-T02 — `WhatsAppCloudAdapter`

> **Built 2026-10-09** (`507d9a6`). Every row of the error table is tested against a fake Graph API. Live check pending: business verification and a production number. `wa-check` (read-only) already works against the test number.
>
> Where the build differs from the text below:
> - **Registered only when `WA_PHONE_NUMBER_ID` is set.** Without it the router records `no_adapter`, which is how the dev server runs today. The Graph version comes from `WA_GRAPH_VERSION` (default `v23.0`; local uses `v25.0`).
> - **Retries are inside the adapter**, at most 3 attempts with doubling backoff, for transient failures only (130429, 131056, 5xx, timeouts).
> - `ChannelException.Permanent` carries `escalateImmediately`, which is set for 131026 and 131049. Neither ever suppresses.

**Files**
```
channels/src/main/java/in/brand/engage/channels/whatsapp/WhatsAppCloudAdapter.java
channels/src/main/java/in/brand/engage/channels/whatsapp/WaGraphClient.java      # declarative client
channels/src/main/java/in/brand/engage/channels/whatsapp/WaPayloads.java
channels/src/main/java/in/brand/engage/channels/whatsapp/WaError.java            # code → permanent | transient | escalate
```

**Error classification** (`WaError`)
| Code | Class | Router/cascade effect |
|---|---|---|
| 131026 | permanent, `escalateImmediately` | capability observation (T05); **no suppression row** |
| 131049 | permanent, `escalateImmediately` | 24 h backoff (T05); no suppression |
| 131047 | permanent | outside window: a bug in our window logic, alert |
| 131050 | permanent | user stopped marketing messages: withdraw WhatsApp marketing consent |
| 132000–132015 | permanent | template problem: pause the template, alert |
| 130429, 131056, 5xx, timeouts | transient | retry with backoff (max 3), never for the codes above |

The router's generic "permanent → suppression" rule from P3-T05 must **not** apply to 131026/131049: a suppression would silently write off the customer that `CLAUDE.md` §4.1 warns about. Put the per-code effect on `PermanentChannelException` (`suppress()`, `escalateImmediately()`), not in the router.

**Rules:** Graph API version pinned by `WA_GRAPH_VERSION`. `to` is `91XXXXXXXXXX` without a plus, from `Msisdn`. Authentication templates are never sent to `91…` numbers in this build.

**Tests:** WireMock-style fake of the Graph API (Micronaut `@Client` against an embedded test server) for each row of the table.

**Done when:** tests pass, and `order_confirmed_v1` reaches a test number from the dev environment.

---

### ◐ P4-T03 — Template sync + number health

> **Built 2026-10-09** (`f1203d0`, `e13b6f3`). Live check pending: nothing has been submitted to Meta. `wa-submit` is a dry run and needs `--yes` plus the owner's approval.
>
> Where the build differs from the text below:
> - **Templates as code**, in `templates/whatsapp/*.yaml`:
>   - eight utility templates, **English only** (owner's decision, 2026-10-10; Hinglish copy was written and then removed before anything was submitted). A test fails the build if a non-English locale is added. The registry still supports more locales (a Hinglish copy would be its own Meta template, `<key>_hi`);
>   - the free-form `wa_stop_confirm`, in category service, is never submitted.
> - **The lint mirrors Meta's review:**
>   - a body of at most 1,024 characters;
>   - no leading, trailing or adjacent variables;
>   - valid names and languages;
>   - https buttons, with the variable at the end;
>   - unique Meta names.
> - **`WaTemplateGate`:** the router sends a template locale only if it is `APPROVED` and its quality is not RED.
> - **Management API placement:** `WaManagementClient` is in the orchestrator, not channels, because ArchUnit keeps the worker out of channels. It is active only with `WA_BUSINESS_ACCOUNT_ID`.
> - **Template sync** runs every 30 min in the worker. It logs category drift as an error and pauses on RED / PAUSED / DISABLED.
> - **Worker commands:** `wa-check`, `wa-sync`, `wa-submit`.
> - **V23:**
>   - `wa_phone_numbers`, with history;
>   - `wa_templates` re-keyed to `(provider_name, language)`;
>   - the `wa.*` config keys.
> - **In T08, `order_shipped_v1` lost `{{eta}}`** (we have no ETA source), and its Track button now opens `/apps/push/t/<ref>` (see T08).

**Migration `V<next>__whatsapp_numbers.sql`**
```sql
CREATE TABLE wa_phone_numbers (
    phone_number_id TEXT PRIMARY KEY,
    display_number  TEXT NOT NULL,
    quality         TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (quality IN ('GREEN','YELLOW','RED','UNKNOWN')),
    messaging_tier  TEXT,                          -- TIER_1K | TIER_10K | TIER_100K | TIER_UNLIMITED
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE wa_phone_number_history (
    phone_number_id TEXT NOT NULL REFERENCES wa_phone_numbers,
    quality         TEXT NOT NULL,
    messaging_tier  TEXT,
    event           TEXT NOT NULL,                 -- FLAGGED | UNFLAGGED | UPGRADE | DOWNGRADE | sync
    observed_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (phone_number_id, observed_at, event)
);

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('wa.service_window_hours', 'CHANNEL','INT','WhatsApp service window (h)', 'Meta: 24.','SAFE',80),
 ('wa.backoff_131049_hours', 'CHANNEL','INT','Back off after 131049 (h)', NULL,'GUARDED',81),
 ('wa.incapable_recheck_days','CHANNEL','INT','Re-check INCAPABLE after (days)', NULL,'GUARDED',82)
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('wa.service_window_hours', '24'::jsonb), ('wa.backoff_131049_hours', '24'), ('wa.incapable_recheck_days', '120')
) AS v(key, val) WHERE config_keys.key = v.key;
```

**Sync job** (worker, every 30 min, and on `message_template_status_update` / `_quality_update` webhooks): list templates from the WABA, upsert `wa_templates` (V4) with `approved_category` = what Meta says. `wa_template_category_mismatch` non-empty → alert. RED quality or `PAUSED` status → local `templates.status = 'paused'` immediately.

**Template files:** `templates/whatsapp/*.yaml` per phase-4 §2, with `provider_name` and the language code actually approved. Lint (P3-T03) additionally checks that `{{n}}` placeholders are contiguous from 1 and match `variables`.

**Done when:** a sync against the real WABA fills `wa_templates` with all 16 rows from P0-T02, and a test that feeds a quality webhook with RED pauses the template before the next decision.

---

### ◐ P4-T04 — Status and inbound handling

> **Built 2026-10-09** (`acee51e`; STOP confirmation in `e13b6f3`). Live check pending, as for T01.
>
> Where the build differs from the text below:
> - **Spend** is booked from the status, once per message (`sends.billed_at`), in the category Meta billed. A drift is logged and counted.
> - **Inbound text is not kept** (`wa_inbound` stores ids only). STOP in English, Hinglish or Hindi:
>   - withdraws WhatsApp entirely and marketing everywhere;
>   - cancels live cascades;
>   - emits `wa_stop`, which sends exactly one free-form confirmation.
>
>   START regrants WhatsApp. "Stop promotions" withdraws WhatsApp marketing only.
> - **Events for the runner:** `wa_read`, `wa_failed` (with `code`), `wa_message_received` (with `context_wamid`), and quick-reply taps under their payload names.
> - **V22:** `sends.billed_at` / `billed_category`, `wa_inbound`.

**Statuses** (`messages.statuses[]`)
- Match `sends.provider_id = wamid`. Advance `delivered_at`, `read_at` monotonically; a late `sent` never un-reads.
- `failed` → `sends.status='failed'`, `failed_reason = code`, then `CapabilityService.observe()`.
- Spend: when the status carries `pricing` with `billable: true`, book **once per wamid** into `spend_ledger` using `pricing.category` and the rate from config. When the billed category differs from `sends.category`, log and alert (`engage_wa_category_drift_total`).
- Success signals to the cascade (T07): `read` for utility runs, `replied`/button for promotional.

**Inbound** (`messages.messages[]`)
- Resolve identity with `Key.waId` + `Key.phone` (STRONG: the number messaged us).
- `profiles.wa_window_until = message time + 24 h`. Capability `CAPABLE`.
- STOP regex from phase-4 §3, plus `START`/`shuru karo` to resubscribe (writes a grant with source `wa_start_reply` and the message id as evidence).
- **WhatsApp marketing is opt-out (decided 2026-10-01, `checkout_notice_v2`), so these are not optional:** every marketing template carries Meta's "Stop promotions" quick reply (or a "Reply STOP" line), which withdraws `whatsapp/marketing` only and keeps order updates; marketing frequency stays low (start at 2 a week, config); watch the number's quality rating daily (P4-T03) and halt marketing on a drop to medium (`halt.marketing`).
- STOP: withdraw WhatsApp transactional and marketing, withdraw marketing on push and email, cancel live cascades with `outcome='stopped'`, and send **one** confirmation as a service message through the router (`isReply=true`). The confirmation is free inside the window it just opened.
- Quick-reply payloads map to events (`wa_optin_yes`, `ndr_reschedule`, `fit_ok`, …).

**Tests:** every fixture in natural and replayed order; `read` before `delivered`; STOP in "band karo", "STOP ", "बंद करो"; STOP replayed → one withdrawal set, one confirmation.

**Done when:** `stop_reply_propagates_to_all_marketing_channels` (CLAUDE.md §10) passes.

---

### ☑ P4-T05 — `CapabilityService`

> **Done 2026-10-09** (`ec435fc`). The seven capability tests of `CLAUDE.md` §10 pass, plus a test that two concurrent same-day 131026 statuses make one strike.
>
> Where the build differs from the text below:
> - **`CapabilityService` lives in core-persistence**, which now depends on core-domain, because statuses arrive in ingest-api. The daily expiry job runs at 03:30 IST in the worker.

Exactly `CLAUDE.md` §4.2 and phase-4 §4, on the V4 `channel_capability` table (`strike_days DATE[]`, `backoff_until`).

**Files:** `orchestrator/src/main/java/in/brand/engage/orchestrator/capability/CapabilityService.java`, `CapabilityRepository.java`, `DeliveryOutcome.java`.

**Rules**
- Strike = locked read-modify-write (`SELECT … FOR UPDATE`), IST calendar day, in Java.
- `CAPABLE` is sticky. 131049 sets `backoff_until` only. Auth + 131026 + `91…` is ignored.
- Daily 03:30 IST: `INCAPABLE` with `recheck_after < now()` → `UNKNOWN`, `strike_days = '{}'`.
- The policy engine reads `backoff_until`: a WhatsApp decision before it → `WA_BACKOFF` block, so the cascade moves to the next step.

**Tests:** the seven capability tests in `CLAUDE.md` §10, plus: two concurrent 131026 statuses for the same identity on the same day → one strike.

**Done when:** all eight pass.

---

### ◐ P4-T06 — SMS via MSG91 with DLT

> **Built 2026-10-10** (`52aa4a1`). Tests: `Msg91AdapterTest` 9, `SmsTemplateTest` 11, `Msg91DlrTest` 4. Live check pending: DLT registration. Every SMS template is `PENDING`, so nothing can be sent yet.
>
> Where the build differs from the text below:
> - **SMS templates use the common template format.** Each is `templates/sms/*.yaml` with an `en` body written with `{{var}}`; the registered DLT text is that body with each variable as `{#var#}`. `dlt_template_id` and `msg91_template_id` are both required.
> - **Until DLT approves a template, its ids say `PENDING`.** The template loads, every send of it fails `dlt_pending` with no HTTP call, and when MSG91 is on, startup logs an ERROR naming them. This replaces a startup failure, so one approved template can go live before the rest.
> - **The lint (startup and build):**
>   - category utility or authentication only; there are no promotional SMS templates;
>   - DLT id of 19 digits, or `PENDING`;
>   - `en` only;
>   - each variable used once;
>   - samples of at most 30 characters, unless the variable is listed in `truncate`;
>   - GSM-7 only, because ₹ would make it Unicode, so the copy says Rs;
>   - one 160-character part with the sample values;
>   - https links only, on a domain in `templates/lint/sms_url_domains.txt`.
> - **Rendering refuses any variable value that holds a link** (links belong in the registered text) **or that is not GSM-7.**
> - **The adapter re-checks the call before any HTTP request:** the id format, slot count equal to value count, and each value at most 30 characters. A failure is permanent (`dlt_mismatch`).
> - **`Msg91Adapter`** uses the Flow API, `POST {base}/api/v5/flow`:
>   - the `authkey` header;
>   - one recipient, with the variables sent by name, so the MSG91 template must use `##order_no##` and so on;
>   - `short_url: 0`.
>
>   It is on only when `MSG91_AUTH_KEY` is set. MSG91's request id becomes `sends.provider_id`.
> - **Delivery reports** arrive at `POST /webhooks/sms?token=<MSG91_DLR_TOKEN>`; MSG91 signs nothing.
>   - Both shapes are accepted: the push DLR (form `data=[{requestId, report:[…]}]`) and Webhook (New) (flat `{requestId, telNum, status, event}`).
>   - Each is normalised into the inbox as source `msg91`.
>   - DELIVERED sets `sends.delivered_at` and emits `sms_delivered`, which the worker turns into the cascade's `delivered` signal.
>   - Other final statuses fail the send as `msg91_<desc>`. A DLT failure reason is logged as an ERROR.
> - **V24:** `rate.sms.utility_paise`, default 20; verify it against the MSG91 rate card.
> - **The plan's `/apps/pay/` link** in `sms_payment_link` became `/apps/push/pay/<ref>`. That redirect does not exist yet; build it with the payment_failed journey.

**Files**
```
templates/sms/sms_order_shipped.yaml, sms_out_for_delivery.yaml, sms_ndr.yaml, sms_payment_link.yaml
channels/src/main/java/in/brand/engage/channels/sms/Msg91Adapter.java
channels/src/main/java/in/brand/engage/channels/sms/SmsTemplate.java        # record from CLAUDE.md §5
channels/src/main/java/in/brand/engage/channels/sms/DltRegistry.java         # startup validation
ingest-api/src/main/java/in/brand/engage/ingest/web/Msg91DlrController.java  # delivery reports → inbox (source 'msg91')
```

```yaml
# templates/sms/sms_order_shipped.yaml
key: sms_order_shipped
channel: sms
category: utility
dlt_template_id: "1107xxxxxxxxxxxxxxx"      # from P0-T03
provider_flow_id: "xxxxxxxx"
body: "Your BRAND order {#var#} has shipped with {#var#}. Track: {#var#} -BRANDN"
variables: [order_no, courier, track_url]
```

**Rules**
- Startup fails if any SMS template lacks `dlt_template_id`, or if the count of `{#var#}` in `body` differs from `variables`.
- Every send checks the rendered variable count again. Each variable is at most 30 characters (the common DLT limit); longer values are truncated only if the template declares it, otherwise a permanent failure.
- URLs in SMS must be on a whitelisted domain registered with DLT; the tracking URL uses the store domain.
- Delivery report `DELIVERED` → `sends.delivered_at` and the cascade's success signal.
- No promotional SMS templates exist, and the registry refuses to load one with `category: marketing`.

**Tests:** missing DLT id fails context start; wrong variable count → permanent failure with no HTTP call; a DLR fixture marks the send delivered.

**Done when:** tests pass and `sms_order_shipped` reaches a test phone with the registered header.

---

### ☑ P4-T07 — Multi-step cascade runner

> **Done 2026-10-10** (`b9e50f3`). `CascadeRunnerTest` (11): guard skip, success signal, ignored signal, cancel within one tick, cancel while claimed, at most 3 deferrals, INCAPABLE straight to SMS, no push for iOS web, escalation (synchronous and by a later status), and SMS `delivered` ends a transactional cascade but not a promotional one.
>
> Where the build differs from the text below:
> - **No `cascade/` sub-package.** `CascadeDefinition` (with `Step`), `Guard` (with `Guard.Context`) and `DefaultOrchestrator` (runner and repository in one) sit in `orchestrator/`. The tick is the existing worker job.
> - **Success signals are per definition**, as `succeedsOn(...)`. The default `{clicked, replied, converted}` is promotional. `onSendSignal(sendId, signal)` maps a provider status back to its run.
> - **Every status change is gated on a live run**, and the run is re-read before each step, so a cancel committed mid-send is never resurrected.
> - **Fix found here:** a claim written as `UPDATE … WHERE id IN (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)` can be re-run by Postgres and claim more than `n`. The campaign executor overran its rate bucket 3×. All four claims (cascade tick, campaign executor, webhook inbox, back-in-stock) now pick in a `MATERIALIZED` CTE.

Implements `CLAUDE.md` §3.2–3.4 with the loop in phase-4 §6.

**Files**
```
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/CascadeDefinition.java    # builder, CLAUDE.md §3.2
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/Step.java
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/Guard.java                # HAS_LIVE_DEVICE, WA_CAPABLE, WA_MARKETING, cartValueAtLeast …
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/GuardContext.java
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/CascadeRunner.java
orchestrator/src/main/java/in/brand/engage/orchestrator/cascade/CascadeRepository.java
worker/src/main/java/in/brand/engage/worker/CascadeTicker.java                            # @Scheduled 10s
```

**Rules**
- Claim with `FOR UPDATE SKIP LOCKED` on `(status, next_step_at)`, 200 at a time; process each run in its own transaction for the claim and the attempt record, with the provider call outside it (same boundary as P3-T05).
- Guard failure → `cascade_attempts.result='skipped'` with the first failing guard, next step **now**.
- `Defer` → same step at `until`, max 3, then next step.
- 131026/131049 (`escalateImmediately`) → next step now.
- `onSignal(identityId, subjectKey, signal)`: one `UPDATE` moving matching live runs to `succeeded` (success signal per `CLAUDE.md` §3.3) or `cancelled` (cancel event). Called from beacons, Meta statuses, DLRs and the event dispatcher for `order_placed`, `cart_emptied`, `order_cancelled`.
- `INCAPABLE` WhatsApp identity: the `WA_CAPABLE_OR_UNKNOWN` guard fails, so the WhatsApp step is skipped with no attempt.

**Tests:** the six cascade tests in `CLAUDE.md` §10, plus: a cancel event committed while the run is claimed → the run ends `cancelled` on the next tick, not after its next send.

**Done when:** all seven pass.

---

### ◐ P4-T08 — `order_tracking`

> **Built 2026-10-10** (`b27d519`). `OrderTrackingTest` (9) runs from events to fake WhatsApp and SMS adapters: the confirmation, then shipped → OFD → delivered, a read stopping the SMS, INCAPABLE straight to SMS, 131026 escalating at once, no opt-in, stale and cancelled cases. Also `TrackRefTest` 3 and `TrackingControllerTest` 3. Live check pending: the dev-store run needs approved templates and DLT.
>
> Where the build differs from the text below:
> - **Intents:**
>   - `order_confirmed`: WhatsApp only;
>   - `order_shipped`: WhatsApp, then SMS after 2 h;
>   - `out_for_delivery`: SMS after 30 min;
>   - `ndr_raised`: SMS after 1 h; success is a reply only (§3.3);
>   - `refund_initiated`: WhatsApp only.
>
>   Each WhatsApp step requires `WA_CAPABLE_OR_UNKNOWN`. Success is `read` or SMS `delivered`.
> - **Only customers with a WhatsApp opt-in get these** (phase-4 §7). Everyone else keeps Shopify's notifications. There is no `WA_OPTED_IN` guard: the journey simply does not start.
> - **Subjects:** `order:<id>`, `shipment:<id>`, `shipment:<id>:ndr`, `refund:<id>`.
> - **A later stage ends the earlier ones:** out for delivery ends `order_shipped` before its SMS; delivered ends all of them, and so does `order_cancelled`.
> - **Nothing is sent** for an event over 24 h old, a cancelled order, or a parcel already delivered, returned or cancelled.
> - **Track links:** the templates pointed at `/apps/track/…`, which does not exist, because the app proxy is `/apps/push`. Links are now `https://wumika.com/apps/push/t/<ref>`:
>   - `<ref>` is a signed shipment reference (`TrackRef`, keyed from the Shopify app secret, at most 25 characters, so it fits a DLT variable);
>   - `TrackingController` checks the proxy signature, then redirects to the courier's page, or shows a short note until there is one.
> - **Tests never reach a real provider.** Every module's test task blanks `WA_PHONE_NUMBER_ID`, `WA_ACCESS_TOKEN`, `WA_BUSINESS_ACCOUNT_ID` and `MSG91_AUTH_KEY`, because `config/local.env` now holds real WhatsApp credentials.

Per phase-4 §7.

```java
CascadeDefinition.of("order_shipped")
    .priority(TRANSACTIONAL)
    .succeedsOn(WA_READ, SMS_DELIVERED)
    .cancelsOn("order_cancelled")
    .step(whatsapp("order_shipped_v1").require(WA_OPTED_IN, WA_CAPABLE_OR_UNKNOWN).waitAfter(Duration.ofHours(2)))
    .step(sms("sms_order_shipped"))
    .build();
```

Same shape for `out_for_delivery` (wait 30 min before SMS), `ndr_raised` (wait 1 h), and single-step WhatsApp for `order_confirmed` and `refund_initiated`. Subject key = order id.

**Operational step:** after this is live on production, **turn off Shopify's SMS shipping notifications** (Settings → Notifications). Keep Shopify's emails.

**Done when:** on the dev store, order → fulfillment → courier OFD produces three utility sends under the policy engine; a test number with WhatsApp gets WhatsApp; a landline-style test number gets SMS after one 131026; `channel_capability` shows the expected states.

---

## Exit gate

- [ ] All phase-4 acceptance criteria ticked
- [ ] `order_tracking` live for 14 days; `wa_template_category_mismatch` empty
- [ ] Capability distribution visible (`CAPABLE`/`UNKNOWN`/`INCAPABLE`); `UNKNOWN` share falling week on week
- [ ] SMS delivery rate ≥ 95% on DLRs (a lower rate usually means a DLT template mismatch)
- [ ] Spend in `spend_ledger` within 2% of Meta's billing for the period

---

## Addendum (2026-10-19): photo templates

The owner liked the photo headers in the old Interakt templates. So seven WhatsApp templates now have a `_v2`, each with the product's photo as an IMAGE header and a bold first line:

| `_v2` | photo | journey |
|---|---|---|
| `order_confirmed_v2` | the most expensive piece in the order | order_tracking |
| `cart_reminder_v2` | the most expensive item in the cart | cart_recovery |
| `checkout_reminder_v2`, `checkout_offer_v2` | the most expensive item in the checkout | checkout_abandon |
| `browse_reminder_v2` | the piece viewed | browse_abandon |
| `price_drop_v2` | the piece | price_drop |
| `back_in_stock_v2` | the piece | back_in_stock |

The `_v1` texts stay. Win-back has no photo: it speaks of new pieces, not one the customer bought.

- **Template files:**
  - `header_image:` is the photo's link, `https://engage.wumika.com/media/product/{{photo}}`.
  - `fallback:` names the `_v1`.
  - The lint requires a fallback with the same category and cooldown that needs no variable the `_v2` lacks.
- **The fallback is chosen before policy.** The router sends the `_v1` instead when the person's product has no photo, or when Meta has not approved every locale of the `_v2`. So the `sends` row, the cooldown and the spend are those of the message that actually went. The journeys name the `_v2` and keep working before Meta approves it.
- **One cooldown:** a `_v2` and its `_v1` count as one message (`TemplateCooldowns.cooldownKeys`).
- **The photo:**
  - The worker adds the photo variable (`<product id>-<8 hex of SHA-256(image_url)>`); a new photo gets a new link, so Meta's cache is bypassed.
  - ingest-api's `/media/product/` fetches `products.image_url`, only from cdn.shopify.com at 1024 px, asking for JPEG or PNG only. Shopify's CDN serves WebP to any client that accepts it, and Meta refuses WebP. Anything else gets a 404.
- **Catalogue:** `products` was filled only by products/update webhooks. `worker products-import` reads the storefront's public `/products.json` once; it is read-only towards Shopify and holds no customer data. On 2026-10-19: 219 products, 147 PNG, 47 WebP, 25 JPG.
- **Submitting:**
  - `wa-submit --yes` downloads each `_v2`'s sample photo (its header link with the sample values) and uploads it with the Resumable Upload API, to the token's own app (`GET /app`, or `WA_APP_ID`).
  - It then submits the template with `example.header_handle`.
