# Phase 5 — Journeys

**Weeks 7–9 · Owner: backend + growth · Depends on: Phase 4; ADR-002 (payment signal)**

## Goal

Turn Shopify, gateway and courier events into the revenue journeys: payment recovery, checkout abandon, cart recovery, post-purchase fit, NDR rescue and win-back. Every journey is a cascade definition in code, and its waits and thresholds are config.

The *why* behind each journey (psychology, copy, channel order) is in `INDIA-PLAYBOOK.md` §3. This phase is about signals, joins, consent scope, and the edge cases that make journeys misfire.

---

## 1. Signal sources

```java
/**
 * Where checkout-stage signals come from. Standard Shopify checkout today; a
 * third-party Indian checkout (Magic Checkout, GoKwik, Shiprocket) later is a
 * new implementation, not a rewrite (overview D3).
 */
public interface CheckoutSignalSource {
    Stream<CheckoutSignal> signals();   // STARTED, CONTACT, PAYMENT_ATTEMPTED, COMPLETED
}
```

| Signal | Standard Shopify source | Carries |
|---|---|---|
| Checkout started / contact entered | `checkouts/create`, `checkouts/update` webhook | phone, email, `cart_token`, `abandoned_checkout_url` |
| Stalled at a step | Web pixel (Phase 2 §7) | checkout token, step, timestamp. No PII |
| Payment **confirmed failed** | Gateway `payment.failed` webhook (ADR-002) | gateway ref, contact, amount |
| Completed | `orders/create` | order, `cart_token`, `checkout_token` |
| Shipped / delivered / NDR | `fulfillments/*` + courier webhook | AWB, status |

---

## 2. Payment failure: two signals, two templates, two categories

This is the most important correction in the build. Meta classifies a message as **utility** when it relates to an action the customer already took: an order, a payment. It classifies an abandoned-cart style nudge as **marketing**. A stalled checkout is not a failed payment, even when it stalls on the payment screen.

### 2.1 Confirmed failure → `payment_failed` (utility)

Trigger: the `payment_failed_confirmed` event, which the Phase 1 ingest service writes for every Razorpay `payment.failed` webhook. The hard part is already done and tested there (`RazorpayInboxHandler`):

- **Join to the checkout.** `razorpay_match_checkout.sql` tries `notes_ref` first: any Razorpay `notes` value equal to a known checkout or cart token. If that finds nothing, it tries `phone_amount_window`: same phone, amount within ₹1, checkout touched in the 30 minutes before the payment. A failure that arrives *before* its checkout webhook is linked when the checkout lands, so delivery order doesn't matter.
- **Amounts** arrive from Razorpay already in paise and are stored unchanged.
- **`failure_kind`** (`TECHNICAL` or `CUSTOMER_CANCELLED`) comes from `error_source` / `error_reason` and picks the second-touch copy. A customer-side UPI *timeout* counts as TECHNICAL: the collect request usually never surfaced in the shopper's UPI app, and they are exactly who "try card or netbanking" is for.

Phase 5 only consumes the event:

```java
@EventHandler("payment_failed_confirmed")
void onConfirmedFailure(Event e) {
    var checkoutToken = e.prop("checkout_token");          // null when match_method = none
    var checkout = Optional.ofNullable(checkoutToken).flatMap(checkouts::byToken);

    orchestrator.dispatch(MessageIntent.builder()
        .identityId(e.identityId())
        .intentKey("payment_failed")
        .subjectKey(checkoutToken != null ? checkoutToken : "pay:" + e.prop("payment_id"))
        .var("amount", Paise.of(e.propLong("amount_paise")).toRupeeString())   // "1,299" — lakh grouping
        .var("ref", e.prop("payment_id"))                    // Razorpay pay_xxx: anchors it to a real transaction
        .var("retry_url", checkout.map(Checkout::recoveryUrl).orElse(storefront.cartUrl()))
        .var("failure_kind", e.prop("failure_kind"))         // chooses the second-touch copy
        .priority(Priority.TRANSACTIONAL)
        .notBefore(e.occurredAt().plus(Duration.ofMinutes(5)))  // see below
        .build());
}
```

`payment_captured` for the same checkout cancels the cascade, as do `order_placed` and a completed checkout.

**Wait five minutes before the first message.** Most UPI failures are retried by the shopper right away, in the same session. A "your payment failed" message that lands while they are on the retry screen reads as the brand not knowing what is happening. The cascade is cancelled by `checkout_completed` or `orders/create`, so shoppers who retry successfully never receive it.

```
t+5min    WhatsApp  payment_failed_v1      utility; opt-in required; ignores quiet hours
t+25min   Push      push_payment_retry     guard: has_live_device
t+2h      WhatsApp  payment_failed_r2_v1   utility; guard: still open; copy chosen by failure_kind
t+6h      SMS       sms_payment_link       guard: WhatsApp incapable or unread
```

The second touch changes the reason, not the volume:
- `TECHNICAL`: "UPI playing up? Card and netbanking work too."
- `CUSTOMER_CANCELLED`: no blame on the bank. Reassure instead: "Still holding your Linen Kurta (M). Free 7-day exchange if the fit isn't right."

### 2.2 Inferred stall → `checkout_abandon` (marketing)

Trigger: pixel `payment_info_submitted` (or `checkouts/update` with contact) and no completion within 30 minutes, with **no** gateway failure event. You do not know the payment failed. They may have hesitated, compared prices, or been interrupted. The copy must not claim a failure it cannot see:

> Hi Priya, your bag is still saved. Free 7-day exchange if the fit isn't right — 12,400 customers have shopped with us.

```
t+30min   Push      push_checkout            guard: has_live_device
t+3h      WhatsApp  checkout_abandon_v1      marketing consent; wa_capable OR cart ≥ ₹2,500
t+20h     Email     email_checkout           Shopify email marketing consent
```

If ADR-002 concluded the gateway webhook cannot be joined, 2.1 does not exist and every payment-stage stall runs through 2.2. It costs about seven times more per WhatsApp message and needs marketing consent. That is why the spike was in Phase 0.

---

## 3. Consent scope is declared by the copy, not assumed

A consent grant covers exactly what the shopper was told. "Send me order updates on WhatsApp" does not cover abandoned-checkout nudges.

Every copy version is registered with the purposes it covers:

```sql
CREATE TABLE consent_copy_versions (
  version      TEXT PRIMARY KEY,           -- wa_v1, ty_wa_v1, push_v1
  channel      channel NOT NULL,
  text         TEXT NOT NULL,              -- verbatim, as displayed
  purposes     TEXT[] NOT NULL,            -- {'transactional'} or {'transactional','marketing'}
  surface      TEXT NOT NULL,              -- cart | thank_you | soft_ask | wa_thread
  created_by   UUID REFERENCES operators(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

A grant made under `wa_v1` writes one `consents` row per purpose in `purposes`. The policy engine never infers coverage from anything else. Registering a copy version is an admin action (Phase 6), and its text is immutable. Changing the words means a new version.

**Recommended copy.** Cart: *"Send me order updates and offers from BRAND on WhatsApp"*. It names the business, the channel and both purposes, which is what Meta requires and what DPDP's purpose-specificity expects. Thank-you page: *"Send delivery and exchange updates from BRAND on WhatsApp"* (transactional only). Just after payment, people want tracking, not offers, and a narrow ask converts better there.

**Email marketing consent comes from Shopify.** Standard checkout on every plan has "Email me with news and offers". Sync `email_marketing_consent` from `customers/create` and `customers/update` into the ledger, with source `shopify_checkout`. Do not reuse Shopify's SMS marketing checkbox for WhatsApp, and do not build promotional SMS at all (Phase 4 §5).

---

## 4. Journey definitions

Waits and thresholds shown are defaults. Each is a config key (`journey.<key>.<param>`), editable in the console with an audit trail.

### `cart_recovery`
```java
CascadeDefinition.of("cart_recovery")
    .priority(PROMOTIONAL)
    .subject(e -> e.cartToken())
    .succeedsOn(CLICKED, CONVERTED)
    .cancelsOn("order_placed", "cart_emptied", "checkout_started")   // checkout_abandon takes over
    .step(push("push_cart").require(HAS_LIVE_DEVICE).waitAfter(hours(4)))
    .step(email("email_cart").require(HAS_EMAIL_MARKETING).waitAfter(hours(14)))
    .step(whatsapp("cart_recovery").require(WA_CAPABLE, WA_MARKETING, cartAtLeast(1_500_00)))
    .build();
```
It starts 45 minutes after the last cart change, and every cart edit restarts it. There is no SMS step (Phase 4 §5). When the shopper starts checkout, this cascade hands over to `checkout_abandon`, so they never receive both.

### `back_in_stock` (extends Phase 3)
Push first, with a 1-hour TTL. WhatsApp 15 minutes later for anyone without a push click, guarded by `WA_CAPABLE` and `WA_MARKETING`. Fan-out is capped at the restocked units × 20, prioritised by who waitlisted earliest.

### `post_purchase_fit`
Trigger: courier `delivered` + 3 days. `delivered_fitcheck_v1` (utility) with quick replies. The reply opens the free service window:

| Reply | Action |
|---|---|
| Fits well | Review request in-thread; UGC ask 5 days later |
| Too small / Too large | Exchange link for the next size, pre-filled. **Exchange, never a refund form.** |
| No reply in 48h | Nothing. Silence is not a problem to chase. |

### `ndr_rescue`
Trigger: courier NDR. `ndr_raised_v1` at t+0 with [Retry tomorrow] [Change address] [Cancel], ignoring quiet hours. SMS at t+3h if there is no reply. Replies post back to the courier's NDR API. An unresolved NDR on a prepaid order still becomes an RTO, and on prepaid it also becomes a refund.

### `winback`
Trigger: nightly job emits `became_lapsed` at 90 days since the last order.
Push → email at +3 days (free) → WhatsApp at +10 days, **guarded on `net_margin_band ∈ {high, mid}`**, meaning returns-adjusted contribution, not gross revenue. At most one win-back cascade per customer per 60 days.

### `price_drop`, `browse_abandon`
Unchanged from Phase 3. Push only.

---

## 5. Collisions between journeys

A single shopper can qualify for four journeys in the same afternoon. The frequency cap stops the fourth message. It does not choose *which* message should win. Priorities do:

| Priority | Journeys | Rule |
|---|---|---|
| 1 Transactional | `payment_failed`, `order_tracking`, `ndr_rescue`, `post_purchase_fit` | Never suppressed by other journeys |
| 2 High intent | `checkout_abandon`, `back_in_stock` | Suppresses 3 and 4 for the same person for 12h |
| 3 Mid intent | `cart_recovery`, `price_drop` | Suppresses 4 for 12h |
| 4 Low intent | `browse_abandon`, `winback` | Yields to everything |

Implement this as a policy check (`HIGHER_PRIORITY_ACTIVE`): a lower-priority step is blocked while a higher-priority cascade is live for the same identity. Blocked, not deferred. When the checkout nudge is running, a browse reminder about a different product is noise.

---

## 6. Exit and conversion

`orders/create` fans out to `orchestrator.onSignal(CONVERTED)` for the order's `cart_token`, its `checkout_token`, and the identity. Every promotional cascade for that shopper stops within one tick, and the conversion is recorded against the last touching send for reporting. For measurement, only the holdout comparison counts (Phase 7).

---

## Acceptance criteria

- [ ] A gateway failure followed by a successful retry within 5 minutes sends nothing
- [ ] A gateway failure with no retry sends `payment_failed_v1` at t+5min, carrying the gateway ref and the recovery URL
- [ ] A payment-stage stall with no gateway event triggers `checkout_abandon` (marketing), never `payment_failed`
- [ ] A shopper with only a `ty_wa_v1` (transactional) grant receives order tracking and is blocked from `checkout_abandon` with `NO_MARKETING_CONSENT`
- [ ] Starting checkout cancels `cart_recovery` and starts `checkout_abandon`; the shopper never gets both
- [ ] With `checkout_abandon` live, a `browse_abandon` step for the same identity is blocked `HIGHER_PRIORITY_ACTIVE`
- [ ] "Too small" on the fit check produces an exchange link for the next size up of the same variant
- [ ] An order placed while three cascades are live terminates all three within one tick

## Exit gate

All journeys live with 10% per-journey holdouts. Two weeks of data. No cross-journey double-messaging found in a manual review of 100 random identities' send timelines.
