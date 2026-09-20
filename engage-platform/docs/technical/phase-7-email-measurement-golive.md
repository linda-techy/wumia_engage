# Phase 7 — Email, measurement and go-live

**Weeks 11–12 · Owner: whole team · Depends on: Phase 6**

## Goal

Add marketing email as the free intermediate channel, turn on honest measurement, harden the system for festive load, and complete the go-live checklist.

---

## 1. Email

### What Engage sends, and what it does not

Shopify already sends the order confirmation, shipping confirmation, refund and account emails, and they are the receipt of record. **Engage does not duplicate them.** Engage email covers only:

- `email_cart`, `email_checkout` (cascade steps)
- `email_browse`, `email_style_guide`, win-back steps
- Campaign email: lookbooks, festive edits, size guides

That is deliberate. In India, email is the weakest conversion channel (`INDIA-PLAYBOOK.md` §1.10). Its value here is that it costs nothing, so it can take the second or third touch in a cascade before a paid WhatsApp message is spent.

### Deliverability setup

| Item | Setting |
|---|---|
| Sending domain | `mail.brand.in`, a subdomain, so marketing reputation never touches the root domain's receipts |
| SPF | include SES for the subdomain |
| DKIM | SES Easy DKIM, 2048-bit |
| DMARC | `p=none` with reporting for 4 weeks, then `p=quarantine` |
| Unsubscribe | `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click` on every marketing email. Gmail and Yahoo require one-click unsubscribe for bulk senders. |
| Bounces / complaints | SES → SNS → ingest; hard bounce and complaint → permanent suppression; complaint also withdraws email marketing consent |
| Complaint rate | Alert above 0.1%. Gmail's bulk-sender threshold is 0.3%, and you do not want to discover it by crossing it. |

**Warm up the domain.** A new sending domain that goes straight to 50,000 emails lands in spam and stays there. Ramp over three to four weeks, sending to the most engaged customers first (opened or clicked in the last 30 days):

```
Week 1   500 / day      engaged in last 30 days
Week 2   2,000 / day    engaged in last 60 days
Week 3   8,000 / day    purchased in last 180 days
Week 4   full list      minus hard bounces and 12-month non-openers
```

Encode the ramp as a config key (`email.daily_cap`) with effective-dated increases, so it advances without anyone having to remember to change it.

### Templates

MJML, compiled at build time, with a plain-text alternative always included. Keep the HTML under 100 KB, because Gmail clips larger messages and hides the unsubscribe link below the fold. Design for a phone first: one column, the product image, a single call to action. The opens that do happen in India are overwhelmingly on mobile.

---

## 2. Measurement

UTMs and last-touch attribution will credit WhatsApp with orders that were going to happen anyway, because WhatsApp read rates are so high. The only honest number is **incremental revenue against a holdout**.

| Holdout | Size | Scope |
|---|---|---|
| Global | 5%, permanent | Receives no marketing on any channel, ever. Utility still flows. |
| Per journey | 10% | Receives everything except that journey |
| Per campaign | set in composer, default 5% | Receives everything except that campaign |

Bucketing is deterministic (SHA-256 of `experiment:identity_id`) and persisted on first use, as specified in `04-backend-micronaut.md`.

**Report per journey, weekly:**

```
Journey              Treated ARPU   Holdout ARPU   Lift    Incremental ₹   Messaging ₹   ROI
payment_failed       ₹412           ₹171           +141%   ₹3,21,000       ₹4,100        78×
checkout_abandon     ₹188           ₹142           +32%    ₹1,05,000       ₹18,900       5.6×
cart_recovery        ₹96            ₹81            +19%    ₹58,000         ₹9,600        6.0×
browse_abandon       ₹31            ₹30            +3%     ₹4,000          ₹0            —
```

*(Illustrative numbers, to show the shape of the report.)*

A journey with a lift inside its confidence interval after four weeks is not working. Switch it off rather than tuning copy indefinitely. Do not report any lift until the holdout has run for at least four weeks. Fashion purchase cycles are long enough that two weeks mostly measures noise.

**Reconcile spend monthly** against Meta's billing export and the FCM console. The `spend_ledger` is booked from status-webhook pricing, and it should match Meta to within 1%. A larger gap means you are missing status webhooks.

---

## 3. Hardening

### Load
Load test the ingest path at 10× steady state (about 400 events/s), the peak you should expect on Diwali and EOSS sale days. Use k6 against staging with a replay of real webhook shapes. Pass criteria: webhook ack p99 < 500 ms, zero Shopify retries, and a journey backlog that drains within 5 minutes after the burst.

### Abuse
The App Proxy and pixel endpoints are public by nature. Rate-limit per `anonId` and per IP. Cap waitlist rows per identity at 50. Reject `copyVersion` values not in the registry. Fuzz every proxy endpoint with malformed bodies. The signature proves the request came through Shopify. It does not prove the request is honest (Phase 1 §5).

### Security review
- A pen test focused on the admin API, token rotation and the App Proxy identity binding
- Secrets rotated before launch: Meta system-user token, Shopify API secret, Firebase service account, JWT signing key
- Backup restore rehearsed on a production snapshot

### Privacy (DPDP)
- Erasure: `POST /customers/{id}/erase` cascades through identity keys, devices, events and sends. Consent withdrawals are kept in anonymised form as proof they were honoured.
- Implement Shopify's compliance webhooks (`customers/data_request`, `customers/redact`, `shop/redact`) and route them to the same erasure and export paths. They are mandatory for App Store apps, and implementing them now removes a blocker if the app is ever listed.

---

## 4. Go-live checklist

**Storefront**
- [ ] App embed enabled on the live theme; service worker registers on Chrome, Edge, Firefox and Samsung Internet
- [ ] iOS and in-app browsers route to the WhatsApp opt-in, never a push prompt
- [ ] Cart opt-in unticked by default; copy version registered; the order webhook grants consent
- [ ] Thank you page extension live; the pending-opt-in race is handled

**Channels**
- [ ] FCM: a production test push reaches Android and desktop with the tab closed
- [ ] WhatsApp: number quality GREEN, all utility templates approved **as utility**, STOP tested in English and Hinglish
- [ ] SMS: DLT header live, variable-count check passing, delivery reports arriving
- [ ] Email: SPF/DKIM/DMARC passing, one-click unsubscribe tested, warm-up schedule configured
- [ ] Shopify's SMS shipping notifications turned off once `order_tracking` is on WhatsApp

**Safety**
- [ ] "Halt all marketing" tested on production and taking effect in under 2 seconds
- [ ] Alerts wired: quality drop, opt-out spike (2× baseline), budget 80% before 14:00 IST, block rate > 40%, stalled cascades
- [ ] Global 5% holdout live and verified by sampling

**Operations**
- [ ] Runbooks from `08-deployment-and-ops.md` walked through by the on-call engineer
- [ ] Journey inspector answers "why did this customer get this message" for 10 random sends

---

## Appendix — native app push (when the apps exist)

The backend needs no new channel. FCM already covers Android and iOS apps.

- **Android:** FCM SDK in the app. Register the token with `platform=ANDROID`. Request `POST_NOTIFICATIONS` (Android 13+) at a moment of intent, never on first launch.
- **iOS app:** upload an APNs authentication key (`.p8`) to Firebase. Register with `platform=IOS_APP`. Once an identity has an `IOS_APP` token, it becomes push-reachable, and the WhatsApp fallback stops being its only route.
- **Deep links:** use Android App Links and iOS Universal Links on the same `url` the web push uses, so a single payload opens the app when installed and the website when not.
- **Payload:** the same data-only contract as Phase 3 §3. The FCM adapter already sets `AndroidConfig`; add `ApnsConfig` with `apns-push-type: alert` and a mutable-content flag if the app renders rich images through a notification service extension.

Once apps exist, `ios_web_identity_never_gets_a_push_step()` still holds: the rule is about the browser, not the person.
