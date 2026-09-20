# Phase 0 — Prerequisites and long-lead items

**Weeks 0–1 · Owner: tech lead + growth lead · Blocks: everything**

## Goal

Start every approval that has an external clock on day one, settle the decisions the code depends on, and stand up an empty repository that builds, tests and deploys.

Most of Phase 0 is waiting on Meta, telecom operators and AWS. The engineering work is small. Starting it late is the most common way this programme slips.

---

## 1. Long-lead items — start on day 1

| Item | Owner | Typical lead time | Blocks |
|---|---|---|---|
| Meta Business verification | Growth + legal | 1–2 weeks | Phase 4 |
| WhatsApp display name approval | Growth | 1–3 days after verification | Phase 4 |
| WhatsApp utility templates (8) | Growth + eng | 1–2 days each; rejections restart | Phase 4, 5 |
| DLT entity registration (TRAI) | Legal | 3–7 days | Phase 4 SMS |
| DLT sender header (6 chars) | Growth | 2–5 days after entity | Phase 4 SMS |
| DLT content templates | Growth + eng | 2–5 days each | Phase 4 SMS |
| SES production access (ap-south-1) | Eng | 1–2 days | Phase 7 |
| Sending domain SPF / DKIM / DMARC | Eng | DNS propagation, <1 day | Phase 7 |
| Shopify protected customer data access | Eng | Same day for custom apps; review for public apps | Phase 1 webhooks, Phase 5 |

### WhatsApp templates to submit now

Submit the utility set first. Utility templates gate Phase 4, and every one of them must pass Meta's classifier as utility. A promotional word re-classifies a template as marketing and changes what it costs.

| Name | Category | Body (variables as `{{n}}`) |
|---|---|---|
| `order_confirmed_v1` | utility | Hi {{1}}, order {{2}} is confirmed. {{3}} · ₹{{4}}. Expected by {{5}}. |
| `order_shipped_v1` | utility | Order {{1}} is on its way with {{2}} (AWB {{3}}). Arriving {{4}}. |
| `out_for_delivery_v1` | utility | Order {{1}} is out for delivery today. |
| `delivered_fitcheck_v1` | utility | Hi {{1}}, your {{2}} was delivered. How is the fit? + 3 quick replies |
| `ndr_raised_v1` | utility | We couldn't deliver order {{1}} ({{2}}). Choose what we should do next. + 3 quick replies |
| `payment_failed_v1` | utility | Hi {{1}}, your payment of ₹{{2}} (ref {{3}}) didn't go through. Your items are still reserved. |
| `exchange_created_v1` | utility | Exchange for order {{1}} is booked. Pickup {{2}}. |
| `refund_initiated_v1` | utility | Refund of ₹{{1}} for order {{2}} is initiated. It reaches your account in {{3}}. |

`payment_failed_v1` carries the gateway **payment reference** as `{{3}}`. That reference is what anchors it to a real transaction and keeps it in the utility category (see Phase 5 §2).

Marketing templates (`cart_recovery`, `checkout_abandon`, `back_in_stock`, `winback`, `vip_early_access`) can follow in week 2. They are not on the critical path.

Submit Hinglish variants alongside the English ones. Each language is a separate approval, and adding them later costs another week.

---

## 2. Decisions that must be written down before code

Record each in `docs/decisions/ADR-nnn.md`. The code branches on these.

**ADR-001 — Shopify plan and checkout.** Confirm the store is on standard checkout without Plus. If the store is on Plus, Phase 2 §6 gains a checkout-step opt-in. If it uses a third-party checkout, Phase 5 changes as described in the overview §4.

**ADR-002 — Payment gateway: Razorpay (decided).** Phase 5's `payment_failed` journey depends on Razorpay's `payment.failed` webhook. The spike in §3 records whether it fires for Shopify payments and how failures join to checkouts.

**ADR-003 — Push prompt surfaces.** Decide which three moments may trigger the soft-ask (default: after add-to-cart, on "Notify me" for a sold-out size, on the Thank you page). No others.

**ADR-004 — Data residency.** Decide on a Postgres region: `ap-south-1` (Mumbai) is recommended for DPDP posture and latency. FCM and Meta are outside your control either way.

---

## 3. Spike: Razorpay payment-failure signal (1 day)

**Gateway: Razorpay** (ADR-002). Phase 5's highest-value journey depends on one fact no documentation can confirm for you: **does Razorpay's `payment.failed` webhook fire for payments started through Razorpay's Shopify payment app, and can each failure be joined to its Shopify checkout?**

The Phase 1 service already records everything the spike needs. There is no request-bin and no throwaway code: run the real ingest service locally (`LOCAL-SETUP.md`) and let the database report the answer.

### Steps

1. **Tunnel.** Start the local service and a tunnel (`LOCAL-SETUP.md` §6). Put the tunnel URL in `PUBLIC_BASE_URL`.
2. **Razorpay webhook (Test Mode).** Dashboard → toggle **Test Mode** → Account & Settings → Webhooks → Add New Webhook.
   - URL: `{PUBLIC_BASE_URL}/webhooks/razorpay`
   - Secret: a long random string you invent. Put the same value in `RAZORPAY_WEBHOOK_SECRET`. This is **not** the API key secret; mixing the two up is the usual reason every signature check fails.
   - Events: `payment.failed`, `payment.captured`, `payment.authorized`
3. **Shopify webhooks** to `{PUBLIC_BASE_URL}/webhooks/shopify`: `checkouts/create`, `checkouts/update`, `orders/create`. The checkouts are the join targets, so they must be flowing too.
4. **Make payments fail, on purpose, on the Shopify store in Razorpay test mode:**
   - UPI: start a payment and let the collect request time out
   - UPI: start a payment and cancel it in the Razorpay window
   - Card: use a Razorpay test card that is declined
   - One successful payment, as a control
   Use a real 10-digit mobile number at checkout, so the phone join can be exercised.
5. **Read the answer:**
   ```sql
   SELECT * FROM payment_failure_match_report;
   SELECT gateway_payment_id, method, error_source, error_reason, match_method, notes
     FROM payment_attempts ORDER BY received_at DESC;
   ```

### What to record in ADR-002

| Question | Where the answer is |
|---|---|
| Does `payment.failed` fire for Shopify-initiated payments at all? | Rows in `payment_attempts` with `status = 'failed'` |
| Which join works? | `match_method`: `notes_ref` (exact — Razorpay put a Shopify reference in `notes`) or `phone_amount_window` (good) or `none` (no join) |
| What does Razorpay put in `notes` for Shopify payments? | The `notes` column. The matcher compares every note value against checkout and cart tokens, so no key name had to be guessed. |
| How do UPI timeouts and cancellations show up? | `error_source` / `error_reason`. These feed `FailureKind`, which chooses the second-touch copy. |
| Webhook latency | `avg_webhook_lag_s` in the report |

### Decision rule

- **≥ 80% matched** (`notes_ref` + `phone_amount_window`): build `payment_failed` as specified, as a **utility** template at about ₹0.12, with the gateway payment id in the message.
- **Fires but mostly `none`**: Razorpay's `contact` did not match the checkout phone. Look at `contact_raw` against the checkout phones before deciding. The usual cause is a different number entered on the Razorpay screen.
- **Does not fire**: `payment_failed` falls back to the inferred signal in Phase 5 §2.2. That is a **marketing** template, which costs about seven times more and needs marketing consent.

Knowing this in week 1 rather than week 8 changes how Phase 5 is planned.

Razorpay delivers webhooks at least once, retries anything not acknowledged with a 2xx within 5 seconds, and only delivers to public URLs, never localhost. The service dedupes on the `x-razorpay-event-id` header, acknowledges straight after storing, and must be reached through a tunnel when running locally.

---

## 4. Accounts and credentials

| System | What to create | Where the secret lives |
|---|---|---|
| Firebase | Project `engage-prod` + `engage-staging`; Web app; **Web Push certificate (VAPID key pair)** | Service account JSON → secret manager |
| Google Cloud | Restrict the Firebase Web API key by HTTP referrer to the store domains | — |
| Shopify | Custom app in Dev Dashboard; scopes below; App Proxy; protected customer data | API secret → secret manager |
| Meta | System user with `whatsapp_business_messaging` + `whatsapp_business_management`; non-expiring token | Secret manager, rotate quarterly |
| MSG91 (or DLT provider) | Account linked to DLT entity id | Secret manager |
| AWS | SES identity for `mail.brand.in`, configuration set, SNS topic for bounces | IAM role, not keys, where possible |

**Shopify scopes:** `read_products`, `read_inventory`, `read_orders`, `read_customers`, `read_checkouts`, `write_pixels`, `read_customer_events`. Request **Level 2 protected customer data** (name, email, phone, address). Without it, checkout webhooks and web pixel events return `null` for exactly the fields this system needs.

**About the Firebase Web API key:** it ships to every browser and is not a secret. It is an identifier. Restricting it by HTTP referrer in the Google Cloud console stops other sites from using your quota. The service-account JSON *is* a secret and must never reach the browser.

---

## 5. Repository skeleton

```
engage/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/libs.versions.toml
├─ core-domain/ policy/ orchestrator/ channels/ journeys/
├─ ingest-api/ admin-api/ worker/
├─ shopify-extension/            ← Shopify CLI app: theme app extension + web pixel
│  ├─ shopify.app.toml
│  └─ extensions/
│     ├─ push-embed/             theme app extension (app embed block)
│     └─ engage-pixel/           web pixel extension
├─ admin-ui/                     Angular 22 workspace
├─ db/migration/                 Flyway
└─ docs/
```

`shopify-extension/` is a Shopify CLI project. It deploys with `shopify app deploy` and is versioned separately from the Micronaut services. The only coupling between them is the App Proxy URL and the ingest endpoint.

```toml
# shopify-extension/shopify.app.toml
name = "engage"
client_id = "<from dev dashboard>"
application_url = "https://engage.brand.in"
embedded = false

[access_scopes]
scopes = "read_products,read_inventory,read_orders,read_customers,read_checkouts,write_pixels,read_customer_events"

[app_proxy]
url = "https://ingest.engage.brand.in/shopify/proxy"
subpath = "push"
prefix = "apps"

[webhooks]
api_version = "2026-07"
```

The webhook API version shown is illustrative. Pin to the current stable Shopify API version on the day you start, and track it in `libs.versions.toml`.

---

## 6. CI

- `./gradlew build` with Testcontainers Postgres. No H2, anywhere.
- An ArchUnit rule that fails the build if any class outside `orchestrator` depends on `channels` (added in Phase 3).
- Template category lint: fails the build if a utility-category template contains promotional language (Phase 3).
- `shopify app build` for the extension.
- `ng build` + Vitest for the console.
- Deploy to staging on merge. Promote to prod manually.

---

## Exit gate

- [ ] Meta business verification submitted; 8 utility templates submitted in English + Hinglish
- [ ] DLT entity application submitted
- [ ] Payment-failure spike done and ADR-002 records the payload and join strategy
- [ ] ADR-001 to ADR-004 merged
- [ ] Firebase projects created; VAPID key generated; API key referrer-restricted
- [ ] Shopify custom app created with App Proxy and protected customer data requested
- [ ] Empty services deploy to staging and return 200 on `/health`
