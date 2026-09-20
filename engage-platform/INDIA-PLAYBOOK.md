# India playbook — psychology, intents, copy

The reasoning layer behind `CLAUDE.md`. Every cascade definition and every template in the build traces back to something in this file.

---

## 1. Ten things about the Indian buyer that change the architecture

### 1.1 Prepaid-only means you carry the entire trust gap

Around 60–65% of Indian ecommerce orders are cash on delivery, and the reflex explanation — "limited card penetration" — is wrong. UPI penetration is enormous; people who pay for everything else by phone still choose COD for a new brand.

COD is **deferred trust**: *I will believe this is real when I am holding it.*

**You are prepaid-only, so that option does not exist.** Every customer must resolve the trust question *before* paying, with no fallback. This is the defining constraint of your programme and it cuts both ways:

- **You give up volume at the top.** A meaningful share of Indian shoppers who would have bought on COD will not convert. Expect a lower checkout conversion rate than a COD-enabled competitor and do not read it as a funnel bug.
- **You get a structurally cleaner business.** Near-zero RTO, no ₹150–300 return-shipping write-offs, no COD remittance delay, no fake orders. Every order that completes is real money.

The strategic consequence: **your messaging programme is a trust-manufacturing system, not a discount-delivery system.** Everything that would have been resolved by "I'll pay when I see it" now has to be resolved before checkout, by evidence.

**Build consequences:**
- Social proof variables (review count, UGC photo, "12,400 sold") belong in *pre-purchase* templates, not post-purchase ones.
- Return and exchange terms go **in the cart-recovery message itself**, not buried in a footer. "Free 7-day exchange" removes more friction than 10% off.
- `order_tracking` is not a courtesy — it is the proof that paying you was safe, and it is what makes the second order possible. Treat it as a conversion journey.

### 1.2 Permanent discounting has broken price anchoring

Indian D2C has trained buyers that the listed price is an opening offer. Two consequences:

- Never run a discount without an expiry you actually honour. A deadline that slips teaches people to ignore all future deadlines.
- **Scarcity beats discount and protects margin.** "Only 2 left in your size" converts comparably to "20% off" and costs you nothing. Use stock-level messaging as the default cart-recovery lever and reserve discounts for the second touch on high-value carts only.

**Build consequence:** `cart_recovery` templates take a `stock_note` variable, not a `discount` variable. The discount lives in a separate, later, value-gated template.

### 1.3 Size anxiety is the number one conversion blocker in fashion

Indian sizing is not standardised across brands. A customer who is M in one label is L in another, and they know it. That uncertainty kills carts and drives returns.

The highest-leverage intervention in the entire system is **conversational size advice on WhatsApp** — and it is nearly free, because it happens inside the 24-hour service window that their own question opens.

**Build consequence:** every post-delivery message asks about fit with quick-reply buttons. "Too small" routes to a one-tap **exchange**, never a refund form. Each exchange saved from becoming a refund is revenue retained plus a reverse-logistics leg avoided.

### 1.4 Social proof outranks brand narrative

Aspirational storytelling works in metros. Everywhere else, "12,400 people bought this" and real customer photos work better. Indian buyers weight peer evidence over brand claims, and the gap widens outside tier-1.

**Build consequence:** review counts and UGC go into template variables. "Loved by 12,400 customers" belongs in the cart-recovery message; the brand's origin story does not.

### 1.5 Purchases cluster around occasions, not seasons

Western retail calendars run on seasons. Indian retail runs on **occasions**: weddings, festivals, family functions. "Wedding season edit" outperforms "new arrivals" by a wide margin from November through February.

**Build consequence:** campaign segmentation should support occasion tagging, and the festive calendar in §4 is the planning spine — not a Gregorian quarter.

### 1.6 WhatsApp is family space

WhatsApp gets 90%+ open rates because it is where their mother messages them. That same intimacy means spam offence is **emotional, not merely functional** — people do not unsubscribe, they block, and a block is permanent and counts against your Meta quality rating.

**Build consequence:** conservative caps (1 marketing message per day, 2 per week) are not timidity. They are what keeps the channel alive. Meta's own per-user limits sit above yours; you should never be the one who hits them.

### 1.7 Hinglish converts better than either pure language

- Metro / tier-1: English is fine
- Tier-2 / tier-3: **Hinglish** ("Aapka order confirm ho gaya hai") beats both formal English and formal Hindi
- Specific geographies: Tamil, Telugu, Bengali, Marathi are worth the template variants

Formal Hindi in a WhatsApp message reads like a government notice. Hinglish reads like a person.

**Build consequence:** templates carry locale variants; pick using the delivery pincode/state, defaulting to Hinglish for non-metro and English for metro.

### 1.8 Evening is the commerce window

Indian ecommerce browsing peaks **8–11pm** — after dinner, once family obligations are done. Secondary peak 10–11am.

This collides with the messaging best practice of stopping at 9pm. The resolution: **7:00–9:00pm is the highest-value marketing slot**, and Sunday evening is the single best slot of the week. Quiet hours start at 21:00 and that boundary is not negotiable for marketing — late-night sends attract blocks, and blocks compound.

**Build consequence:** default quiet hours 21:00–09:00 IST. Campaign scheduler defaults to 19:30 IST.

### 1.9 Prepaid-only moves your leak from post-dispatch to the payment page

A COD business loses money after dispatch, to RTO. You lose it at the payment step instead — which is better, because that leak is recoverable and RTO is not.

Three distinct drop-off points, each needing a different response:

| Where they leave | Why | What recovers it |
|---|---|---|
| Cart → checkout | Still evaluating; no commitment made | Scarcity, social proof, return terms |
| Checkout → payment | Trust wobble at the moment of paying | Return policy, secure-payment reassurance, real reviews |
| **Payment attempted → failed** | **UPI timeout, bank OTP failure, gateway drop** | **Immediate retry link — this is pure recovery, not persuasion** |

That third row is the important one. UPI and netbanking failures are common in India, and the customer had already decided to buy — intent is fully demonstrated, nothing needs selling. You are removing a technical obstacle, which is why it recovers at rates nothing else in the funnel approaches.

It is **utility category only when the failure is confirmed by the payment gateway**. A message that says "your payment (ref X) didn't go through" relates to a real payment attempt, which Meta treats as utility, at roughly ₹0.12 to recover a full-value order. A checkout that stalls at the payment screen with no confirmed failure is an abandoned checkout. Meta classifies that as **marketing**, and the message must not claim a failure you cannot see. That is the difference between rows two and three above, and it is why `docs/technical/phase-0-prerequisites.md` §3 proves the gateway signal with a real test payment before Phase 5 is planned.

**Build consequence:** `payment_failed` fires 5 minutes after a confirmed failure. Most UPI failures are retried straight away in the same session, and the cascade cancels if the retry succeeds. It ignores quiet hours and is the first journey to build. Keep the items reserved while the cascade runs and say so in the message — "your items are still held" is the line that stops them re-searching for the product elsewhere.

### 1.10 Email is not the conversion channel here

8–15% open rates, buried in Gmail's Promotions tab, and most Indian consumers under 35 treat email as a place for receipts and work. Building an India D2C programme around email drip sequences is importing a US playbook that does not transfer.

Email's real jobs: receipts and invoices, long-form editorial, **zero-cost win-back** (you can email six times for free; you cannot WhatsApp six times at any price), and deliverability insurance if your WhatsApp number ever gets restricted.

**Build consequence:** email appears in cascades as a free intermediate step between push and WhatsApp — never as the primary step for a time-sensitive intent.

---

## 2. Channel role assignment

| Channel | Owns | Never used for |
|---|---|---|
| WhatsApp utility | Order, payment failure, shipping, delivery, NDR, returns, exchanges | Anything with an offer in it |
| WhatsApp marketing | Drops, restocks on wishlisted items, VIP early access, high-AOV win-back | List blasts, weekly newsletters |
| WhatsApp service window | Size advice, styling help, order queries | Being ignored — it is the cheapest conversion surface you have |
| Push (web/Android) | Drops, price drops, browse abandon, cart nudges, back-in-stock | Anything needing detail or trust |
| Push (iOS app) | Same as Android | — |
| iOS web | **Nothing.** Cannot receive push. WhatsApp covers these users. | — |
| SMS | Delivery OTP, last-mile alerts, WhatsApp-incapable numbers | Marketing (DND blocks it, ROI is poor) |
| Email | Invoices, editorial, size guides, free win-back, loyalty statements | Time-critical anything |

No IVR channel. It existed only to confirm COD orders before dispatch; a prepaid-only store has nothing to confirm. Drop the adapter from the build.

---

## 3. Intent catalogue

Every message the system can send. Each entry is a `CascadeDefinition` in code.

### `payment_failed` — highest ROI in the system. Build this first.
**Trigger:** gateway-confirmed payment failure (`payment.failed` webhook) · **Priority:** TRANSACTIONAL · **Category:** utility throughout · **Cancels on:** checkout completed, order placed

```
t+5min    WhatsApp  payment_failed        ignores quiet hours; carries the gateway payment ref
t+25min   Push      push_payment_retry    guard: has_live_device
t+2h      WhatsApp  payment_failed_r2     guard: still unpaid, items still held
t+6h      SMS       sms_payment_link      guard: wa_incapable OR wa_unread
```

Nothing else in the funnel recovers at this rate, because nothing needs selling — the customer already decided to buy and a gateway failed them. You are removing an obstacle, not persuading anyone.

Five minutes, not zero. A "your payment failed" message that lands while the shopper is already on the retry screen reads as the brand not knowing what is happening. Wait long enough for the in-session retry and no longer. Quiet hours do not apply: someone whose payment failed at 22:30 is still at their phone and still wants the order.

**Needs WhatsApp opt-in.** Meta requires opt-in for utility messages too. On the first order, that opt-in comes from the cart checkbox (`docs/technical/phase-2-shopify-web-push.md` §6.1). Without it, the cascade runs on push and SMS only.

**Hold the inventory and say so.** "Your items are still reserved" is the line that stops them re-searching the product and finding it cheaper somewhere else. If you cannot actually hold it, do not claim it.

> Hi Arun, your payment of ₹1,299 didn't go through — your items are still reserved.
> `[Complete payment]`

Second touch changes the reason, not the volume. If UPI failed, offer a different rail:

> Still holding your Oversized Tee (M). UPI playing up? Card and netbanking work too.
> `[Try again]`

### `checkout_abandon` — prepaid-only makes this your biggest leak
**Trigger:** contact entered, then no completion within 30 minutes and **no** gateway-confirmed failure. This includes stalls on the payment screen. · **Category:** marketing

```
t+30min   Push      push_checkout         guard: has_live_device
t+3h      WhatsApp  checkout_abandon      guard: WhatsApp marketing consent AND (wa_capable OR cart ≥ ₹2,500)
t+20h     Email     email_checkout        guard: Shopify email marketing consent
```

Distinct from `cart_recovery` and far more valuable: they reached the payment step, which means they were ready. Something at the moment of paying stopped them, and on a prepaid-only store that something is almost always trust.

So this message carries **trust signals, not discounts** — return terms, review count, secure-payment reassurance:

> Hi Priya, your bag is still saved. Free 7-day exchange if the fit isn't right — 12,400 customers have shopped with us.
> `[Complete order]`

This is also where you capture a phone number even when the order never completes, which feeds capability discovery (see `CLAUDE.md` §4.3).

### `order_tracking` — the trust engine
**Trigger:** every logistics event · **Category:** utility

Confirmed → shipped (with AWB) → out for delivery → delivered → NDR → return picked → refund initiated. Sent on WhatsApp to every customer with a WhatsApp opt-in, with SMS fallback on shipped, out-for-delivery and NDR. Shopify's own emails stay on as the receipt of record. Turn off Shopify's SMS shipping notifications once this is live, or every parcel produces two texts.

Second-order benefit most brands miss: these messages get **replies**, and every reply opens a free 24-hour service window where you can actually sell.

### `cart_recovery` — channel escalates with intent
**Trigger:** cart updated, non-empty · **Cancels on:** order placed, cart emptied

```
t+45min   Push      push_cart          guard: has_live_device
t+4h      Email     email_cart         guard: email marketing consent (free)
t+18h     WhatsApp  cart_recovery      guard: WhatsApp marketing consent AND wa_capable AND (cart ≥ ₹1,500 OR repeat customer)
```

The WhatsApp step is value-gated because ₹0.86 against a ₹600 cart is not a trade worth making. There is no SMS step, because promotional SMS is not built (DND blocks it for a large share of Indian numbers). Starting checkout hands the shopper over to `checkout_abandon`, so nobody gets both. Lead with scarcity, not discount.

> Hi Priya, your Linen Shirt in M is still in your bag. Only 2 left in your size.
> `[Complete order]`

### `browse_abandon` — free channels only
**Trigger:** 3+ PDP views, no add-to-cart

```
t+4h      Push      push_browse
t+20h     Email     email_browse
```

No WhatsApp step at all. Intent is too weak to justify a marketing template — this is exactly the discipline that keeps your WhatsApp bill from eating the margin the channel was meant to protect.

### `back_in_stock` — the marketing template that earns its cost
**Trigger:** variant restocked (0 → positive transition only)

```
t+0       Push      push_back_in_stock    guard: has_live_device
t+15min   WhatsApp  back_in_stock         guard: wa_capable
```

The only broadcast-style marketing message that reliably pays for itself, because the customer explicitly asked to be told. Waitlist at the **size-variant** level, not the product level. Short TTL (1h) and high urgency — a restock alert two days late is worse than none.

### `ndr_rescue` — speed is the entire value
**Trigger:** NDR raised · **Category:** utility

```
t+0       WhatsApp  ndr_raised        [Retry tomorrow] [Change address] [Cancel]
t+3h      SMS       sms_ndr               guard: no reply
```

An unresolved NDR becomes an RTO. Ignores quiet hours.

### `post_purchase_fit` — exchange, not refund
**Trigger:** delivered + 3 days · **Category:** utility

```
t+3d      WhatsApp  delivered_fitcheck    [Fits well] [Too small] [Too large]
t+7d      Email     email_style_guide
t+25d     Push→WA   cross-sell, adjacent category, guard: no order in 30d
```

"Too small" opens a one-tap size swap. "Fits well" routes to a review request, then a UGC ask. This message is the best-performing service-window opener in the whole system.

### `winback` — capped attempts, gated on margin
**Trigger:** 90-day lapse

```
t+0       Push      push_drop
t+3d      Email     email_browse           (free — use this generously)
t+10d     WhatsApp  winback                guard: net_margin_band IN (high, mid)
```

Gate the WhatsApp step on **returns-adjusted contribution**, not gross revenue. A high-AOV customer with a 60% return rate is not a VIP and should not receive a ₹0.86 template.

### `vip_early_access`
**Trigger:** drop scheduled · **Audience:** top decile by net margin

24-hour early access over WhatsApp. One of the few places a marketing template consistently beats its cost, and it doubles as a trust signal.

---

## 4. Festive calendar

The revenue spine. Plan campaigns against this, not against quarters.

| Window | Occasion | Note |
|---|---|---|
| Jan | Republic Day + EOSS | Deep discounting expected |
| Apr–May | Akshaya Tritiya | Auspicious buying |
| Jun–Jul | Monsoon sale, EOSS | |
| Aug 15 | Independence Day | |
| Aug–Sep | **Onam** (Kerala) | Regional — segment by state |
| Sep–Oct | **Durga Puja** (Bengal/East) | Regional, very high intent |
| Oct | Navratri / Dussehra | |
| **Oct–Nov** | **Diwali** | The peak. Plan 6 weeks out. |
| Nov–Feb | **Wedding season** | Highest AOV of the year |
| Dec | Christmas / New Year | Metro-weighted |

**Campaign pattern — escalate channels, suppress between waves:**

```
D-3   Email teaser                     free
D-1   Push                             free
D0    Push + on-site                   free
D+1   WhatsApp → non-openers only      paid, narrow
D+2   WhatsApp → cart-holders only     paid, narrower
D-last Push + WhatsApp → engaged-unconverted
```

Suppression between waves is what stops this destroying your quality rating. Raise caps for a festive window using an **effective-dated** config change that reverts automatically — the real failure mode is elevated caps outliving the campaign that justified them.

---

## 5. Measurement

WhatsApp open rates above 90% make every campaign look like a triumph and tell you nothing about whether it caused a sale.

- Permanent **5% global holdout**, never messaged, plus per-journey holdouts on the big ones
- Report **incremental ARPU lift against holdout** alongside cost per message — never open rate alone
- Two ratios reviewed monthly: **marketing-to-utility message mix** (drive it down) and **blocks + opt-outs per thousand sends** (watch it like an incident metric)

A campaign with a 94% open rate, ₹40,000 spend and 0.3% ARPU lift over holdout lost money. The dashboard should say so plainly.

**Leading indicator:** opt-out rate rising is the earliest warning of a quality-rating drop, usually by a few days. Alert on 2× the 7-day baseline.

---

## 6. Trust, as engineering rather than copy

- Official Business Account green tick, free from Meta via your BSP or direct
- Visible opt-out in every marketing template — an easy exit prevents a block, and blocks are what sink you
- Never send a marketing template dressed as an order update. Customers notice, and so does Meta's classifier.
- Honour STOP across **all** channels within minutes via the shared consent ledger
- Publish exchange and return terms inside the post-purchase flow, not buried in a footer
- Quality rating treated as an incident metric: High → Medium pages someone
