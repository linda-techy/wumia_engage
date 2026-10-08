# P4 — WhatsApp, SMS, cascades · Implementation

**Weeks 5–7 · Owners: BE1 (WhatsApp, capability), BE2 (SMS, cascade runner) · Design:** [`technical/phase-4-whatsapp-sms-orchestrator.md`](../technical/phase-4-whatsapp-sms-orchestrator.md), `CLAUDE.md` §3–5

**Blocked until:** P0-T01 (Meta verified, number approved), P0-T02 (utility templates approved **as UTILITY**), P0-T03 (DLT header and templates). T01, T05 and T07 can be built and tested against fakes before the approvals land; nothing is sent to a real customer until they have.

## Outcome

Order updates reach opted-in customers on WhatsApp, fall back to DLT SMS when WhatsApp cannot deliver, and teach the system which numbers have WhatsApp, all for utility prices. Multi-step cascades run on the worker. STOP in English, Hinglish or Hindi withdraws consent everywhere it should.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P4-T01 | Meta webhook intake | BE1 | 1 d | P3 done |
| P4-T02 | `WhatsAppCloudAdapter` + error classification | BE1 | 1.5 d | T01 |
| P4-T03 | Template sync + V17 number health | BE1 | 1.5 d | T02 |
| P4-T04 | Status and inbound handling: spend, service window, STOP | BE1 | 2 d | T01, T02 |
| P4-T05 | `CapabilityService` | BE1 | 1.5 d | T04 |
| P4-T06 | `Msg91Adapter` + DLT registry + delivery reports | BE2 | 2 d | P3 done |
| P4-T07 | Multi-step cascade runner + `onSignal` | BE2 | 2.5 d | P3 done |
| P4-T08 | `order_tracking` journey | BE1 + BE2 | 1.5 d | T02–T07, P1-T02 |

---

### ☐ P4-T01 — Meta webhook intake

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

### ☐ P4-T02 — `WhatsAppCloudAdapter`

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

### ☐ P4-T03 — Template sync + number health

**Migration `V17__whatsapp_numbers.sql`**
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

### ☐ P4-T04 — Status and inbound handling

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

### ☐ P4-T05 — `CapabilityService`

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

### ☐ P4-T06 — SMS via MSG91 with DLT

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

### ☐ P4-T07 — Multi-step cascade runner

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

### ☐ P4-T08 — `order_tracking`

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
