# P0 — Prerequisites · Implementation

**Weeks 0–1 · Owners: Growth (approvals), BE1 (engineering tasks) · Design:** [`technical/phase-0-prerequisites.md`](../technical/phase-0-prerequisites.md)

## Outcome

Every approval with an external clock is submitted on day 1. The decisions the code branches on are written down. CI builds and tests every push. The Razorpay spike has produced a number.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P0-T01 | Meta business verification + WhatsApp number | Growth | 1–2 wk elapsed | — |
| P0-T02 | Submit utility WhatsApp templates (EN + Hinglish) | Growth + BE1 | 1 d + review | T01 |
| P0-T03 | DLT registration: entity, header, SMS templates | Growth | 1–2 wk elapsed | — |
| P0-T04 | Firebase projects + Web Push certificate | BE1 | 0.5 d | — |
| P0-T05 | Shopify app, App Proxy, protected customer data, CLI project | BE1 | 1 d | — |
| P0-T06 | **Razorpay payment-failure spike** | BE1 + Growth | 1 d | P1-T01 |
| P0-T07 | SES sending domain + production access | BE2 | 0.5 d + review | — |
| P0-T08 | CI pipeline | BE2 | 1 d | — |
| P0-T09 | ADRs 001–005 | Tech lead | 0.5 d | — |

---

### ☐ P0-T01 — Meta business verification + WhatsApp number

**Steps**
1. Meta Business Manager → Security Centre → start business verification (legal name, GST, address proof).
2. Create a WhatsApp Business Account. Add a phone number that is **not** registered on the WhatsApp consumer app.
3. Submit the display name.
4. Create a system user with `whatsapp_business_messaging` and `whatsapp_business_management`. Generate a non-expiring token and store it in the secret manager, never in git.

**Done when:** verification shows *Verified*, the display name is *Approved*, and `WA_PHONE_NUMBER_ID` / `WA_BUSINESS_ACCOUNT_ID` are recorded in the secret manager.

---

### ☐ P0-T02 — Submit utility templates

Submit the eight utility templates from the design doc (§1): `order_confirmed_v1`, `order_shipped_v1`, `out_for_delivery_v1`, `delivered_fitcheck_v1`, `ndr_raised_v1`, `payment_failed_v1`, `exchange_created_v1`, `refund_initiated_v1`. Submit an English and a Hinglish variant of each.

**Rules that decide approval category**
- No promotional words: *sale, offer, % off, shop now, limited time, flat ₹*. One of these makes a template **marketing**, about seven times the price.
- `payment_failed_v1` must carry the Razorpay payment id as a variable (`{{3}}`). That reference to a real transaction is what makes it utility.

**Done when:** all 16 are *Approved* **as UTILITY**. Record the category Meta approved, not the one you requested. A mismatch means rewrite and resubmit.

---

### ☐ P0-T03 — DLT registration (SMS)

1. Register the principal entity on an operator DLT portal (Jio, Airtel, Vi or BSNL). Record `DLT_ENTITY_ID`.
2. Register a 6-character sender header, e.g. `BRANDN`.
3. Register four **service/transactional** content templates: `sms_order_shipped`, `sms_out_for_delivery`, `sms_ndr`, `sms_payment_link`. Variables must be `{#var#}` slots; record each template's DLT id.
4. Link the entity to MSG91 (or your SMS provider).

No promotional SMS templates. DND blocks them for a large share of Indian numbers, and the build does not send promotional SMS.

**Done when:** header and all four templates are approved, and their DLT ids are recorded for `templates/sms/*.yaml` (P4-T05).

---

### ☐ P0-T04 — Firebase

1. Create projects `engage-staging` and `engage-prod`, and add a **Web app** to each.
2. Project settings → Cloud Messaging → **Web Push certificates** → generate a key pair. The public key is `FIREBASE_WEB_VAPID_PUBLIC_KEY`.
3. Project settings → Service accounts → generate a key. Save it as `config/firebase-service-account.json` locally (git-ignored) and in the secret manager for staging/prod.
4. Google Cloud console → APIs & Services → Credentials → the Firebase **Browser key** → restrict HTTP referrers to your store domains.

**Done when:** a test message from the Firebase console reaches a sample page registered with the VAPID key. (A throwaway HTML page is fine; the real one arrives in P2.)

---

### ☐ P0-T05 — Shopify app and CLI project

1. `npm init @shopify/app@latest shopify-extension` at the repo root. Choose "no template"; this app only hosts extensions.
2. In `shopify-extension/shopify.app.toml` set `access_scopes`, `[app_proxy]` and `[webhooks]` exactly as the design doc §5 and `LOCAL-SETUP.md` §6.3 show.
3. Dev Dashboard → your app → **API access → Protected customer data**: request Level 2 (name, email, phone, address), with the reasons (order updates, payment recovery).
4. Install on your development store and record the offline Admin API token as `SHOPIFY_ADMIN_TOKEN` (needed from P1-T03).

**Done when:** `shopify app deploy` succeeds, the proxy URL responds (404 from the service is fine), and a test order's webhook arrives with the phone field populated.

---

### ☐ P0-T06 — Razorpay payment-failure spike

**Depends on P1-T01**, because it uses the real ingest service, not a request-bin.

Follow [`technical/phase-0-prerequisites.md` §3](../technical/phase-0-prerequisites.md): Razorpay Test Mode webhook → tunnel → make UPI and card payments fail on purpose → `SELECT * FROM payment_failure_match_report;`.

**Done when:** ADR-002 records:
- whether `payment.failed` fires for Shopify payments
- the match rate by method (`notes_ref` / `phone_amount_window` / `none`)
- what Razorpay puts in `notes`
- the `error_reason` values seen for UPI timeout and for cancel

**Decision rule:** a match rate of 80% or more means P5-T02 is built as **utility**. Anything lower goes to the tech lead before Phase 5 is planned.

---

### ☐ P0-T07 — SES

1. Verify the identity `mail.brand.in` in `ap-south-1` and enable Easy DKIM (2048-bit).
2. Publish SPF, DKIM and DMARC (`p=none; rua=...`).
3. Request production access (it lifts the sandbox).
4. Create configuration set `engage` with an SNS event destination for bounce and complaint.

**Done when:** SES shows *Production* and a test email passes SPF, DKIM and DMARC in Gmail's "Show original".

---

### ☐ P0-T08 — CI pipeline

**Files**
```
.github/workflows/ci.yml
```

**Pipeline**
1. Postgres 16 service container. Create `engage_app` and `engage_test` with `scripts/db-setup.sql`.
2. JDK 25 (`actions/setup-java`, distribution `temurin`). Gradle cache.
3. Write a CI-only `config/local.env` from repository **secrets**, pointing at the service container with **test** Shopify and Razorpay secrets.
4. `./gradlew build` (unit + DB + end-to-end tests).
5. `psql -f db/tests/invariants.sql` against the migrated test database.
6. From P2: `npm ci && npm run build` in `shopify-extension/`. From P6: `ng build && npx vitest run` in `admin-ui/`.

**Done when:** a PR shows all checks green, and a deliberately broken test turns it red.

---

### ☐ P0-T09 — ADRs

Create `docs/decisions/`:

| ADR | Decision |
|---|---|
| ADR-001 | Standard Shopify checkout, non-Plus. Revisit if Plus or a third-party checkout is adopted. |
| ADR-002 | Razorpay. Spike result appended by T06. |
| ADR-003 | Push prompt surfaces: add-to-cart, notify-me, Thank you page. |
| ADR-004 | Postgres in `ap-south-1`. |
| ADR-005 | **Shipping aggregator: Shiprocket** (decided 2026-09-30, `docs/decisions/ADR-005-shipping-aggregator.md`). Decides the courier webhook adapter in P1-T02 and the NDR reply API in P5-T07. |

---

## Exit gate

- [ ] T01–T03 submitted (approval may still be pending; submission is the gate)
- [ ] T04, T05, T07, T08, T09 done
- [ ] T06 done, ADR-002 records the match rate
