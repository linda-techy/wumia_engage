# Technical design — phased delivery

**System:** Engage, the unified messaging platform for a prepaid-only Indian D2C fashion brand
**Storefront:** Shopify (Online Store 2.0 theme, standard Shopify checkout)
**Channels:** Web push via FCM · WhatsApp Cloud API · SMS (DLT) · Email (SES)
**Stack:** Micronaut 5 / Java 25 · Postgres 16 · Angular 22 admin console
**Status:** Build-ready. Last revised 19 Sep 2026.

This document set is the phased build plan. `CLAUDE.md` holds the invariants and `INDIA-PLAYBOOK.md` holds the marketing reasoning; neither is repeated here. Where a design is already specified in `docs/01`–`08`, this set links to it instead of restating it.

---

## 1. System context

```
                 ┌──────────────────────── Shopify ────────────────────────┐
                 │                                                         │
  Shopper ──────▶│  Theme (OS 2.0)                      Checkout           │
  browser        │  └─ App embed: push.js ──┐           └─ Web pixel ──┐   │
                 │                          │                          │   │
                 │  App proxy  /apps/push/* │           Webhooks       │   │
                 └──────────────┬───────────┴───────────────┬──────────┼───┘
                                │ signed query               │ HMAC     │ beacon
                                ▼                            ▼          ▼
                 ┌──────────────────────── Engage ─────────────────────────┐
                 │  ingest-api    service worker, token registry, events   │
                 │  worker        cascades, journeys, campaign executor    │
                 │  admin-api  ◀── Angular console                         │
                 │                                                         │
                 │  Postgres 16  identity · consent · devices · sends ·    │
                 │               cascade_runs · config_versions · audit    │
                 └──────┬───────────────┬────────────────┬──────────┬──────┘
                        ▼               ▼                ▼          ▼
                       FCM         Meta Cloud API     MSG91 (DLT)   SES
                   (web push)        (WhatsApp)          (SMS)     (email)

  Razorpay (payment gateway) ── payment.failed / captured webhooks ──▶ ingest-api
```

Three runtimes, one codebase, one database. The architecture and its reasoning are in [`01-architecture.md`](../01-architecture.md).

---

## 2. Phase map

| Phase | Name | Weeks | Ships to customers | Doc |
|---|---|---|---|---|
| 0 | Prerequisites & long-lead items | 0–1 | — | [phase-0](phase-0-prerequisites.md) |
| 1 | Core platform | 1–2 | — | [phase-1](phase-1-core-platform.md) |
| 2 | **Shopify web push (FCM)** | 2–4 | Push opt-in live, tokens collecting | [phase-2](phase-2-shopify-web-push.md) |
| 3 | Policy engine + push sending | 4–5 | Back-in-stock, browse and cart push | [phase-3](phase-3-policy-and-push-sending.md) |
| 4 | WhatsApp, SMS fallback, orchestrator | 5–7 | Order tracking on WhatsApp | [phase-4](phase-4-whatsapp-sms-orchestrator.md) |
| 5 | Journeys | 7–9 | Payment recovery, checkout abandon, post-purchase | [phase-5](phase-5-journeys.md) |
| 6 | Admin console + campaigns | 9–11 | Manual campaigns via console | [phase-6](phase-6-admin-console-campaigns.md) |
| 7 | Email, measurement, go-live | 11–12 | Win-back email, holdout reporting | [phase-7](phase-7-email-measurement-golive.md) |

Twelve weeks for two backend engineers and one frontend engineer. The critical path runs through Phase 0 approvals, not through code.

### Why this order

**Push before WhatsApp.** Web push has zero marginal cost and no third-party approval gate, so it is the fastest way to put working infrastructure in front of real traffic. It also starts accumulating subscribers from week 3. Every week push ships late is a week of visitors who left without a token.

**The policy engine before the second channel.** Phase 3 builds the one-door router with a single channel behind it. Adding WhatsApp in Phase 4 then only adds an adapter. Building both channels first and retrofitting policy is how per-channel caps end up inconsistent.

**Journeys before the console.** Automated journeys drive most of the revenue. Manual campaigns are the riskier feature and should arrive once the safety rails have been proven on automation.

**Email last.** Shopify already sends order confirmations and shipping emails. What remains for Engage is marketing email, which carries the least conversion weight in India (see `INDIA-PLAYBOOK.md` §1.10).

---

## 3. Dependency graph

```
Phase 0 ──┬──▶ Phase 1 ──▶ Phase 2 ──▶ Phase 3 ──┬──▶ Phase 5 ──▶ Phase 6 ──▶ Phase 7
          │                                      │      ▲
          │    Meta WABA verified ───────────────┴▶ Phase 4
          │    DLT headers + templates approved ───▶ Phase 4 (SMS)
          │    SES production access ─────────────────────────────────────▶ Phase 7
          └─── Gateway webhook spike ─────────────────▶ Phase 5 (payment_failed)
```

Phase 4 cannot start until Meta has verified the business and approved the utility templates. Submit these in Phase 0, week 0. Verification alone can take one to two weeks, and a rejected template restarts the clock.

---

## 4. Decisions carried into the build

| # | Decision | Consequence |
|---|---|---|
| D1 | Prepaid only, no COD | No COD confirmation or IVR. Payment recovery becomes the top journey. |
| D2 | FCM for all push | One adapter covers web, Android and iOS apps. The HTTP v1 API is used through the Firebase Admin SDK. |
| D3 | Standard Shopify checkout, non-Plus | No UI extensions on the checkout steps. Opt-in capture happens on the cart and the Thank you page (Phase 2 §6). |
| D4 | Meta Cloud API direct, no BSP | No per-message markup. Template and quality operations are owned in-house. |
| D5 | Postgres only in v1 | `SKIP LOCKED` queues. No Redis, no Kafka. |
| D6 | Shopify sends transactional email | Engage does not duplicate order or shipping emails. |

**Revisit D3 when:** the store moves to Shopify Plus, or adopts a third-party Indian checkout (Razorpay Magic Checkout, GoKwik, Shiprocket Checkout). Those checkouts collect an OTP-verified phone number on their first screen and emit their own abandonment webhooks. That changes Phase 5 materially for the better. Implement Phase 5 behind a `CheckoutSignalSource` interface so the swap is an adapter rather than a rewrite.

---

## 5. Corrections to earlier documents

Research during this revision corrected three claims in the earlier docs. All three are fixed at source. They are listed here because they change the build.

**1. Checkout opt-in checkbox.** `CLAUDE.md` §4.3 recommended a WhatsApp opt-in checkbox on the checkout page. Checkout UI extensions on the information, shipping and payment steps are **Shopify Plus only**. Non-Plus stores can extend only the Thank you and Order status pages. Phase 2 §6 replaces the checkbox with a cart-drawer opt-in plus a Thank-you-page opt-in.

**2. Payment failure category.** `payment_failed` qualifies as a **utility** template only when it references a real, gateway-confirmed payment attempt. A checkout that stalls at the payment step without a confirmed failure is an abandoned checkout, and Meta classifies abandoned-cart reminders as **marketing**. Those need marketing consent and cost about seven times more. Phase 5 §2 splits the two signals and gives each its own template.

**3. WhatsApp opt-in covers utility too.** The earlier policy engine treated transactional messages as implicitly consented on every channel. Meta requires opt-in before **any** business-initiated WhatsApp message, including order updates, and the opt-in must name the business and the channel. Implied transactional consent still holds for SMS and for email. Phase 3 §1 changes the consent check. Phase 2 §6 is where the opt-in is collected.

---

## 6. Definition of done — whole programme

- Every outbound message traverses `MessageOrchestrator.dispatch()` → policy → router. This is enforced by an ArchUnit test (Phase 3).
- Web push opt-in rate is ≥ 4% of sessions shown the soft-ask, measured, not estimated.
- Dead FCM tokens are pruned within one send cycle of FCM reporting them.
- WhatsApp quality rating is HIGH on all numbers at go-live, with a paging alert wired.
- Every send, blocked send and config change is reconstructable from Postgres alone.
- The 5% global holdout has been live for four weeks before any lift is reported.

---

## 7. Sources

- [Authenticate app proxies — Shopify](https://shopify.dev/docs/apps/build/online-store/app-proxies/authenticate-app-proxies)
- [App proxy requests include logged-in customer ID — Shopify changelog](https://shopify.dev/changelog/app-proxy-requests-include-new-parameter-for-the-logged-in-customer-id)
- [Shopify checkout extensibility by plan](https://www.fudge.ai/blog/shopify-checkout-extensibility/)
- [Web pixel checkout events and data redaction](https://weltpixel.com/blogs/news/what-customer-data-is-available-in-shopify-web-pixel-events-and-what-shopify-redacts)
- [Web Pixels API — payment_info_submitted](https://shopify.dev/docs/api/web-pixels-api/standard-events/payment_info_submitted)
- [WhatsApp template category rules — utility vs marketing](https://help.egrow.com/en/article/whatsapp-template-category-guide-how-to-keep-your-templates-in-the-utility-category)
- [Get opt-in for WhatsApp — Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in)
- [Best practices for FCM registration token management — Firebase](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [iOS PWA and web push limitations, 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide)
