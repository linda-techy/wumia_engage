# P5 — Journeys · Implementation

**Weeks 7–9 · Owners: BE1, BE2 · Design:** [`technical/phase-5-journeys.md`](../technical/phase-5-journeys.md), [`INDIA-PLAYBOOK.md`](../../INDIA-PLAYBOOK.md)

**Blocked until:** ADR-002 records the Razorpay spike result (P0-T06). A match rate below 80% means T03 is re-planned with the tech lead before this phase starts.

## Outcome

The revenue journeys run through the orchestrator: payment recovery (utility), checkout and cart recovery (marketing), back-in-stock on WhatsApp, post-purchase fit check, NDR rescue and win-back. Journeys collide safely by priority. Every journey has a holdout so its lift can be measured in P7.

**Email steps.** Several journeys have an email step. The SES adapter arrives in P7-T01. Until then, those steps are declared and guarded by `channelLive(EMAIL)`, which is false, so they are skipped with a recorded reason. No journey definition changes when email goes live.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P5-T01 | Journey framework + collisions + V19 | BE1 | 2 d | P4-T07 |
| P5-T02 | `payment_failed` (utility) | BE1 | 1.5 d | T01, ADR-002 |
| P5-T03 | `checkout_abandon` (marketing) | BE1 | 1.5 d | T01 |
| P5-T04 | `cart_recovery` (full) | BE2 | 1 d | T01 |
| P5-T05 | `back_in_stock` WhatsApp step | BE2 | 0.5 d | T01 |
| P5-T06 | `post_purchase_fit` | BE2 | 1.5 d | T01, P4-T04 |
| P5-T07 | `ndr_rescue` + courier NDR reply | BE2 | 1.5 d | T01, ADR-005 |
| P5-T08 | `winback` + nightly profile recompute | BE1 | 2 d | T01 |
| P5-T09 | Holdouts on every journey | BE1 | 0.5 d | T02–T08 |

---

### ☐ P5-T01 — Journey framework

**Migration `V19__journey_config.sql`**
```sql
INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('journey.payment_failed.first_delay_minutes', 'JOURNEY','INT','Payment failed: first message after (min)',
  'Most UPI failures are retried at once. Too early reads as the brand not knowing.','GUARDED',100),
 ('journey.checkout_abandon.stall_minutes',     'JOURNEY','INT','Checkout stall after (min)', NULL,'GUARDED',101),
 ('journey.checkout_abandon.wa_min_cart_paise', 'JOURNEY','INT','Checkout abandon: WhatsApp min cart (paise)', NULL,'CRITICAL',102),
 ('journey.cart_recovery.start_delay_minutes',  'JOURNEY','INT','Cart recovery starts after (min)', NULL,'GUARDED',103),
 ('journey.cart_recovery.wa_min_cart_paise',    'JOURNEY','INT','Cart recovery: WhatsApp min cart (paise)', NULL,'CRITICAL',104),
 ('journey.winback.lapsed_days',                'JOURNEY','INT','Lapsed after (days)', NULL,'GUARDED',105),
 ('journey.winback.cooldown_days',              'JOURNEY','INT','Win-back at most once per (days)', NULL,'GUARDED',106),
 ('journey.collision.suppress_hours',           'GLOBAL', 'INT','Higher priority suppresses lower for (h)', NULL,'GUARDED',107)
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES      -- default_value added in V8
  ('journey.payment_failed.first_delay_minutes', '5'::jsonb),
  ('journey.checkout_abandon.stall_minutes',     '30'),
  ('journey.checkout_abandon.wa_min_cart_paise', '250000'),   -- ₹2,500
  ('journey.cart_recovery.start_delay_minutes',  '45'),
  ('journey.cart_recovery.wa_min_cart_paise',    '150000'),   -- ₹1,500
  ('journey.winback.lapsed_days',                '90'),
  ('journey.winback.cooldown_days',              '60'),
  ('journey.collision.suppress_hours',           '12')
) AS v(key, val) WHERE config_keys.key = v.key;

-- The collision check reads cascade_runs_identity_live_idx, which V4 already created.

CREATE FUNCTION profile_recompute(p_identity UUID) RETURNS void LANGUAGE sql AS $$
  INSERT INTO profiles (identity_id, computed, updated_at)
  SELECT p_identity,
         jsonb_build_object(
           'orders_count',   count(o.*),
           'last_order_at',  max(o.created_at),
           'net_paise_365d', coalesce(sum(o.total_paise - o.refunded_paise)
                                FILTER (WHERE o.created_at > now() - interval '365 days'
                                          AND o.cancelled_at IS NULL), 0)),
         now()
    FROM orders o WHERE o.identity_id = p_identity
  ON CONFLICT (identity_id) DO UPDATE
    SET computed = profiles.computed || EXCLUDED.computed, updated_at = now();
$$;
```

**Files**
```
journeys/src/main/java/in/brand/engage/journeys/Journey.java              # interface: definition() + triggers
journeys/src/main/java/in/brand/engage/journeys/JourneyRegistry.java
journeys/src/main/java/in/brand/engage/journeys/JourneyParams.java        # typed read of journey.<key>.<param>
policy/src/main/java/in/brand/engage/policy/CollisionCheck.java           # HIGHER_PRIORITY_ACTIVE
```

**Collision rule** (phase-5 §5): a step of a cascade with priority P is blocked `HIGHER_PRIORITY_ACTIVE` when the identity has a live cascade with priority < P that is 2 or 3, or had one that ended less than `journey.collision.suppress_hours` ago. Priority 1 (transactional) never blocks and is never blocked. Blocked, not deferred.

**Events the journeys need that P1 does not emit yet.** Add them to `ShopifyInboxHandler` in this task, with tests in the three orderings:
| Event | Emitted when |
|---|---|
| `checkout_started` | the first `checkout_updated` for a checkout token (dedupe key `checkout_started:<token>`) |
| `checkout_completed` | a checkout webhook with `completed_at` set |
| `cart_emptied` | `cart_updated` with zero line items |

**Journey rules**
- A journey is an `EventConsumer` that builds a `MessageIntent`. It never calls the router or an adapter (ArchUnit already fails the build if it tries).
- `halt.journey` and `journey.enabled` are checked by the policy engine, not in the journey.
- Money thresholds come from config as paise. Literal defaults are written `2_500_00`, never `250_00`.

**Tests:** the collision table as a parameterised test (4 × 4 priorities); a transactional cascade never blocked; the suppression window expires.

**Done when:** tests pass and `JourneyRegistry` lists every journey with its priority on worker startup.

---

### ☐ P5-T02 — `payment_failed` (utility)

Consumes `payment_failed_confirmed`, which `RazorpayInboxHandler` already writes with `payment_id`, `amount_paise`, `checkout_token` (nullable), `match_method` and `failure_kind`. Steps and copy: phase-5 §2.1.

```
t+5min    WhatsApp  payment_failed_v1      require WA_OPTED_IN, WA_CAPABLE_OR_UNKNOWN; quiet-hours exempt
t+25min   Push      push_payment_retry     require HAS_LIVE_DEVICE
t+2h      WhatsApp  payment_failed_r2_v1   require CHECKOUT_STILL_OPEN; copy by failure_kind
t+6h      SMS       sms_payment_link       require WA_INCAPABLE_OR_UNREAD
```

- Subject key: `checkout_token`, or `pay:<payment_id>` when unmatched.
- `notBefore = occurred_at + first_delay_minutes`, taken from the **event** time, so a late webhook does not push the message later still. If `now()` is already past t+30 min when the event is processed, skip step 1 and start at the push.
- Cancelled by `payment_captured` for the same checkout, `order_placed`, `checkout_completed`.
- Unmatched failures (`match_method='none'`) still send if the identity has a WhatsApp opt-in; the retry URL falls back to the cart.
- `payment_failed_v1` carries the Razorpay `pay_…` id as `{{3}}`. Without it, Meta may classify the template as marketing.

**Tests:** failure then capture at t+3 min → nothing sent; failure alone → WhatsApp at t+5; failure delivered 40 min late → first touch is push; `TECHNICAL` vs `CUSTOMER_CANCELLED` pick different r2 copy; transactional-only consent is enough.

**Done when:** the four acceptance criteria in phase-5 about payment failure pass as tests, and a Razorpay test-mode failure on staging produces the WhatsApp at t+5.

---

### ☐ P5-T03 — `checkout_abandon` (marketing)

Trigger: a checkout with contact (phone or email) and `last_step` set, not completed within `stall_minutes`, with **no** `payment_failed_confirmed` for it. Implemented as a delayed check: on `checkout_updated`, dispatch the intent with `notBefore = +stall_minutes`; step 0 is a guard-only step that verifies the stall is still true.

```
t+30min   Push      push_checkout            require HAS_LIVE_DEVICE
t+3h      WhatsApp  checkout_abandon_v1      require WA_MARKETING, (WA_CAPABLE or cart ≥ wa_min_cart_paise)
t+20h     Email     email_checkout           require channelLive(EMAIL), EMAIL_MARKETING
```

- Starting this cascade cancels `cart_recovery` for the same cart.
- The copy never claims a failure. Lint it: `checkout_abandon*` templates must not contain "fail", "declined", "unsuccessful".
- A `payment_failed_confirmed` arriving later for the same checkout cancels this run and starts `payment_failed`.

**Tests:** stall with no gateway event → `checkout_abandon`; gateway failure → `payment_failed` only; transactional-only grant → WhatsApp step blocked `NO_MARKETING_CONSENT`, push still sent.

---

### ☐ P5-T04 — `cart_recovery` (full)

Replaces the P3 single-step definition with phase-5 §4. Start delay 45 min after the **last** cart change; every change restarts the run. Cancelled by `order_placed`, `cart_emptied`, `checkout_started`. WhatsApp only if `WA_CAPABLE` (never `UNKNOWN`: marketing does not pay to discover capability) and cart ≥ `wa_min_cart_paise`.

**Tests:** cart edit at t+40 min restarts; checkout start cancels and hands over; ₹1,499 cart → WhatsApp guard fails; ₹1,500 → passes.

---

### ☐ P5-T05 — `back_in_stock` WhatsApp step

Adds `whatsapp("back_in_stock_v1").require(WA_CAPABLE, WA_MARKETING)` 15 minutes after the push, for runs without a push click. The fan-out cap (restocked quantity × 20) applies to the run count, not per step. If stock hits 0 again before the WhatsApp step, the run is cancelled (`variant_sold_out` event from P1-T03's inventory handler, emitted on positive → 0).

---

### ☐ P5-T06 — `post_purchase_fit`

Trigger: `order_delivered` + 3 days. `delivered_fitcheck_v1` (utility) with three quick replies. Reply handling in the P4-T04 inbound path emits `fit_ok`, `fit_small`, `fit_large`.

- `fit_ok` → review request as a service message in the open window; UGC ask 5 days later only if the window is reopened.
- `fit_small`/`fit_large` → exchange link for the next size of the same product, built from the order line's variant and the product's size option order. **Never a refund link.**
- No reply in 48 h → nothing.
- Skip orders with a refund or cancellation.

**Tests:** size S → "too small" → link for M of the same product; largest size → "too large"-only path offers the smaller size, "too small" offers a size chat instead; refunded order → no fit check.

---

### ☐ P5-T07 — `ndr_rescue`

Trigger: `delivery_failed` (P1-T02). `ndr_raised_v1` at t+0, quiet-hours exempt, quick replies [Retry tomorrow] [Change address] [Cancel]. SMS `sms_ndr` at t+3 h without a reply. Success is a **reply only**, not a read.

Replies call the aggregator's NDR action API (ADR-005) through a `CourierActions` interface in `ingest-api`'s courier package, implemented per aggregator. The API call happens outside the transaction, with the result recorded as an event (`ndr_action_sent` / `ndr_action_failed`). "Change address" opens a Shopify order-status link, not free text in chat.

**Tests:** read without reply → SMS at t+3 h; reply "Retry tomorrow" → courier API called once even if the webhook is replayed.

---

### ☐ P5-T08 — `winback` + nightly recompute

**Nightly job** (01:30 IST, advisory lock): for identities with an order, call `profile_recompute`; then compute `net_margin_band` (`high`/`mid`/`low` by tertile of `net_paise_365d`) and `aov_band`; emit `became_lapsed` once when `last_order_at` crosses `lapsed_days`, dedupe key `lapsed:<identity>:<last_order_at>`.

```
t+0       Push      push_winback      require HAS_LIVE_DEVICE
t+3d      Email     email_winback     require channelLive(EMAIL), EMAIL_MARKETING
t+10d     WhatsApp  winback_v1        require WA_CAPABLE, WA_MARKETING, marginBand(high, mid)
```

At most one win-back run per identity per `cooldown_days` (a guard reading `cascade_runs`).

**Done when:** the job processes 100,000 synthetic identities in under 5 minutes on staging, and a test identity with a high-return history gets no WhatsApp step.

---

### ☐ P5-T09 — Holdouts on every journey

Each journey's key is its holdout experiment. Default control share 10% for marketing journeys, **0% for transactional ones** (`payment_failed`, `order_tracking`, `ndr_rescue`, `post_purchase_fit`): withholding a delivery update to measure it is not acceptable. The global holdout (V2 `holdout.global_pct`) applies to marketing only.

**Done when:** after 7 days on staging traffic, `SELECT experiment, bucket, count(*) FROM holdouts GROUP BY 1,2` shows the configured split within ±1 point, and control identities have `HOLDOUT_CONTROL` rows in `sends`.

---

## Exit gate

- [ ] All phase-5 acceptance criteria ticked
- [ ] `payment_failed` recovery rate measured over 14 days: orders within 24 h of a sent message ÷ failures messaged, against the holdout
- [ ] No identity received more than the configured caps in any 24 h (query in `docs/qa/p5-caps.sql`)
- [ ] `wa_template_category_mismatch` still empty after the new templates
