# P7 — Email, measurement, go-live · Implementation

**Weeks 11–12 · Owners: BE2 (email, deploy), BE1 (measurement, privacy), FE (reports) · Design:** [`technical/phase-7-email-measurement-golive.md`](../technical/phase-7-email-measurement-golive.md), [`08-deployment-and-ops.md`](../08-deployment-and-ops.md)

## Outcome

Marketing email takes the free second and third touches in cascades. Every journey reports incremental revenue against its holdout. The system has survived a 10× load test, a security review and an erasure drill, and the go-live checklist is signed.

**Decision (owner, 2026-10-15):** the platform concentrates on WhatsApp, SMS and push. Marketing email is sent with **Shopify Email** (its abandoned-checkout automation and newsletters), not through Engage. The SES channel built in T01/T02 stays off (no `EMAIL_FROM`), so journeys skip their email steps (`channel_off`) and nobody gets a duplicate email from us. The warm-up and the engaged-audience guard are not built; build them only if email comes back into Engage.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P7-T01 | `SesAdapter` + SNS bounce/complaint intake + one-click unsubscribe | BE2 | 2 d | P0-T07 |
| P7-T02 | MJML templates + warm-up ramp | FE + BE2 | 1.5 d | T01 |
| P7-T03 | Measurement: lift views migration, reports, spend reconciliation | BE1 + FE | 2 d | P5-T09 |
| P7-T04 | Load test (k6) | BE2 | 1.5 d | P6 done |
| P7-T05 | Privacy: `erase_identity()`, Shopify compliance webhooks | BE1 | 1.5 d | P1 done |
| P7-T06 | Security review + abuse hardening | BE1 + BE2 | 2 d | P6 done |
| P7-T07 | Production deployment | BE2 | 1.5 d | T04, T06 |
| P7-T08 | Go-live | All | 1 d | everything |

---

### ◐ P7-T01 — Email channel

> **Built 2026-10-15 on fakes** (no SES account yet; P0-T07). Tests: `SesAdapterTest` 5, `SigV4Test` 2 (AWS's published vectors), `SnsSignatureVerifierTest` 4, `UnsubscribeTokenTest` 2, `EmailFeedbackTest` 6, `EmailTemplateTest` 3, `EmailStepTest` 1. Live check pending: the SES domain (SPF, DKIM, DMARC), production access, and the SNS topic.
>
> Where the build differs from the text below:
> - **No AWS SDK.** `SesAdapter` calls SES v2 with the JDK HTTP client and its own Signature V4 (`SigV4`), like the WhatsApp and MSG91 adapters. The adapter is on only with `EMAIL_FROM` set; credentials are `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (+ optional `AWS_SESSION_TOKEN`). EC2 instance roles are not read yet.
> - **Unsubscribe:** `List-Unsubscribe` carries `<PUBLIC_BASE_URL/u/{token}>` (+ the mailto when `EMAIL_UNSUBSCRIBE_MAILTO` is set) and `List-Unsubscribe-Post`. `UnsubscribeToken` (core-domain) is HMAC-signed and keyed from the Shopify app secret, so it never expires. `GET /u/{token}` shows a page and changes nothing (mail scanners open links); `POST` withdraws email marketing once (an `email_unsubscribed` event) and is a no-op after that.
> - **Bounces:** `POST /webhooks/ses` checks signature version 2, a certificate from `sns.<region>.amazonaws.com` (fetched and cached by `SnsCertificates`) and `SES_SNS_TOPIC_ARN`; it auto-confirms the subscription. `SesInboxHandler` turns a hard bounce into a permanent suppression and three soft bounces in 7 days into a 30-day suppression. A complaint suppresses and withdraws email marketing.
> - **`channelLive` is generic:** a step whose channel has no adapter is skipped (`channel_off:<channel>`) without waiting, for email and any other channel.
> - **Email steps** go last in `cart_recovery` (+20 h after the push), `checkout_abandon` (+12 h after the offer step) and `winback` (+3 d after the offer step), guarded by `HAS_EMAIL_MARKETING`. Placed last, they don't shift the other steps' timing while email is off.

**Files**
```
channels/src/main/java/in/brand/engage/channels/email/SesAdapter.java           # SES v2 SendEmail, configuration set 'engage'
channels/src/main/java/in/brand/engage/channels/email/UnsubscribeTokens.java    # HMAC-signed, per identity + list
ingest-api/src/main/java/in/brand/engage/ingest/web/SesSnsController.java       # SNS → webhook_inbox (source 'ses')
ingest-api/src/main/java/in/brand/engage/ingest/web/UnsubscribeController.java  # GET page + POST one-click
core-domain/src/main/java/in/brand/engage/aws/SnsSignatureVerifier.java
```

**Rules**
- Every marketing email carries `List-Unsubscribe: <https://…/u/{token}>, <mailto:…>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`. `POST /u/{token}` withdraws email marketing with no login and no confirmation page, returns 200, and is idempotent.
- SNS messages are verified (signature version 2, SHA-256; the signing certificate URL must be an `sns.<region>.amazonaws.com` host) before they touch the inbox. `SubscriptionConfirmation` is confirmed automatically only for the configured topic ARN.
- Hard bounce → permanent `suppressions` row. Complaint → suppression **and** withdrawal of email marketing consent. Soft bounce → nothing until 3 in 7 days.
- Engage does not send transactional email; Shopify does. The adapter refuses `category != marketing`.
- `channelLive(EMAIL)` from P5 turns true when this adapter is registered **and** `halt.channel` for email is false. Email steps in existing journeys start sending with no definition change.

**Tests:** SNS vectors (valid, tampered, wrong cert host); bounce and complaint fixtures; one-click POST twice → one withdrawal; a utility email is refused.

**Done when:** a test email passes SPF, DKIM and DMARC in Gmail's "Show original", and the Gmail unsubscribe link in the header works.

---

### ◐ P7-T02 — Templates + warm-up

> **Templates built 2026-10-15; warm-up not yet.**
>
> Where the build differs from the text below:
> - **No MJML.** Email templates are YAML like the others (`templates/email/*.yaml`: title = subject, body = paragraphs, one URL button), rendered into one branded table layout in code (`EmailLayout`). That gives inline styles, a preheader, a footer with the unsubscribe link, and an HTML-escaped plain-text alternative, with no Node toolchain in the Java build.
> - **Lint:** marketing only, subject ≤ 70 characters with the samples, exactly one URL button, rendered HTML < 100 KB.
> - **Templates:** `email_cart_v1`, `email_checkout_v1`, `email_winback_v1`.
> - **Not built yet:** the `email.daily_cap` warm-up and the `email_engaged_days` guard. Build them before the first real send.

- `templates/email/*.mjml` compiled with `mjml` at build time into `templates/email/dist/*.html`, plus a `.txt` alternative for each. The lint (P3-T03) fails a build whose HTML exceeds 100 KB or lacks the unsubscribe placeholder.
- Warm-up: config key `email.daily_cap` (added in the P7 migration) with **effective-dated versions**, entered by an operator through the P6 config API (so each has an actor and a reason), for weeks 1–4 (500, 2,000, 8,000, then removed). The policy engine counts today's email marketing sends against it and defers the rest to tomorrow 09:00 IST.
- Audience for weeks 1–3 is further restricted by an `email_engaged_days` guard (opened or clicked in 30/60 days, or purchased in 180).

**Done when:** four `config_versions` rows exist for `email.daily_cap` with consecutive effective windows, and a test shows the 501st email on day 1 deferred.

---

### ◐ P7-T03 — Measurement

> **Built 2026-10-15 (`V35__measurement.sql`); the staging "done when" waits for staging data.**
>
> Where the build differs from the text below:
> - **No `email.daily_cap`.** Email is Shopify Email's job (owner's decision 2026-10-15), so there is no warm-up cap. V35 holds only the two views, an index on `holdouts (experiment, bucket, assigned_at)`, `meta_billing (month, category, amount_paise)` and `spend_reconciliations`.
> - **Report:** `GET /api/reports/lift?days=56` gives one row per experiment (`global` plus each journey key), and `GET /api/journeys/{key}/incrementality?days=30` gives one journey (404 before it has a holdout). Both are ANALYST. Revenue per person is net of refunds, leaves out cancelled orders, counts only orders after the person entered the experiment and within the window, and counts people who bought nothing as zero. `LiftStats.welch` gives the 95% interval: Welch–Satterthwaite df, with the t quantile from a Cornish–Fisher expansion that is within 0.01 of the tables from df 3. "Incremental" is the per-person difference × treated count, with the same interval. Messaging cost is `sends.cost_paise` for the journey's intent; for `global` it is every non-campaign marketing send. ROI = incremental ÷ cost. `enoughData` needs the experiment to be 28 days old **and** 30 people in each group, and `significant` is never true without it. Each row also carries the weekly breakdown from `holdout_lift_weekly`.
> - **Meta bill:** `POST /api/reports/meta-billing` (CONFIG_ADMIN, audited `meta_billing.upload`, `text/csv`) takes `month,category,amount_inr`, one month per upload; a second upload of the same month replaces it. Errors name the line. `GET /api/reports/spend-reconciliation?month=YYYY-MM` (VIEWER) compares the WhatsApp `spend_ledger` with the bill per category and flags anything over 1% off.
> - **Job:** `SpendReconciliation`, run by `WorkerJobs.reconcileSpend` at **06:00 IST on the 1st to the 15th**, not just the 1st: Meta's invoice lands a few days into the month. It checks last month once the bill is uploaded, records each category in `spend_reconciliations`, and logs an ERROR for more than 1% off. Until the bill is uploaded it logs a WARN each morning. FCM is free and is not reconciled.
> - **Console:** a Reports page (nav "Reports"). It shows journey lift in staff words with the 95% range, "not enough data yet: …" and "could still be chance", plus last month's ledger against the bill with the CSV upload for config admins.
> - **Tests:** `LiftStatsTest` (table quantiles, a worked example, a seeded 20% lift inside its interval, no lift not significant), `ReportsTest` (lift, cost and ROI on seeded buckets; the 4-week and 30-person rules; roles; reconciliation flagging; a bad CSV), `SpendReconciliationTest`.

**Migration `V<next>__measurement_and_privacy.sql`** (lift views part)
```sql
-- Net revenue per bucket per IST week, with the bucket size as it stood at the
-- end of that week. The denominator counts identities with no orders, which is
-- what makes treated vs holdout an honest comparison.
CREATE VIEW holdout_lift_weekly AS
WITH rev AS (
  SELECT h.experiment, h.bucket,
         date_trunc('week', o.created_at AT TIME ZONE 'Asia/Kolkata')::date AS week_ist,
         sum(o.total_paise - o.refunded_paise)                            AS net_revenue_paise
    FROM holdouts h
    JOIN orders o ON o.identity_id = h.identity_id
                 AND o.created_at >= h.assigned_at
                 AND o.cancelled_at IS NULL
   GROUP BY 1, 2, 3
)
SELECT r.experiment, r.week_ist, r.bucket, r.net_revenue_paise,
       (SELECT count(*) FROM holdouts h2
         WHERE h2.experiment = r.experiment AND h2.bucket = r.bucket
           AND h2.assigned_at < ((r.week_ist + 7)::timestamp AT TIME ZONE 'Asia/Kolkata')) AS identities
  FROM rev r;

CREATE VIEW spend_by_intent_daily AS
SELECT (s.created_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist,
       split_part(s.intent_key, ':', 1)                   AS intent,
       s.channel, s.category,
       count(*) FILTER (WHERE s.status NOT IN ('blocked','deferred')) AS sends,
       coalesce(sum(s.cost_paise), 0)                     AS cost_paise
  FROM sends s
 GROUP BY 1, 2, 3, 4;

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, default_value) VALUES
 ('email.daily_cap', 'CHANNEL', 'INT', 'Email marketing / day (warm-up)',
  'Effective-dated ramp during domain warm-up. Remove once warm.', 'GUARDED', 90, '0')
ON CONFLICT (key) DO NOTHING;
```

`0` means no cap. ARPU for a week is `net_revenue_paise / identities`. A bucket with no orders in a week has no row; the report treats it as zero revenue, not as missing.

**Report** (`admin-api` `GET /reports/lift` + a UI table in the P6 app): per journey and week, treated vs holdout ARPU, lift with a 95% confidence interval (Welch's t on per-identity revenue, computed in Java from a per-identity query, not from the aggregate view), incremental ₹, messaging ₹ from `spend_by_intent_daily`, ROI. The UI shows "not enough data" until 4 weeks of holdout exist.

**Reconciliation job** (monthly, 1st, 06:00 IST): compare `spend_ledger` for the previous month with the Meta billing export (uploaded as CSV through the admin API) and FCM sends; alert when the WhatsApp difference exceeds 1%.

**Done when:** on staging data with a seeded 20% true lift, the report shows lift inside its CI; and the reconciliation job flags a deliberately removed status webhook.

---

### ☐ P7-T04 — Load test

`load/k6/` with scenarios replaying real webhook shapes (fixtures from P1–P4, re-signed per request): Shopify checkouts/orders/carts, Razorpay, Meta statuses, pixel events. Ramp to 400 events/s for 15 minutes on staging with production-sized Postgres.

**Pass:** webhook ack p99 < 500 ms; zero 5xx; inbox backlog drains within 5 minutes after the burst; cascade ticker lag < 30 s; no Hikari pool exhaustion. Record results in `docs/qa/p7-load.md`.

---

### ◐ P7-T05 — Privacy

> **Built 2026-10-15 (`V36__privacy.sql`). Still to do: deploy the compliance webhook subscriptions, and the staging erasure drill.**
>
> Where the build differs from the text below:
> - **Migration V36**, not a shared one with T03. It adds `erase_identity(identity, request_ref, actor)`, with helpers `privacy_scrub_orders`, `privacy_scrub_inbox`, `privacy_like_patterns` and `privacy_text`, plus `consent_erasure_proofs`, `privacy_requests`, the tombstone identity `00000000-0000-0000-0000-000000000000`, and `pii_unmask_log.field` `data_request`.
> - **What erasure covers beyond the plan:** the whole merge tree (start from any id, merged-away or survivor); orders, checkouts and payments matched by phone or email even when not linked to the identity; raw webhook payloads in every source; courier `shipment_events.raw`; anonymous pixel events; export files that mention the person. Matching folds spaces and hyphens between digits, so "+91 98765 43210" is caught. An unprocessed inbox row that mentions them is closed (`last_error` says why), so it cannot re-create them. Sends move to the tombstone with `idempotency_key` and `decision` rewritten.
> - **Not erased:** `audit_log` and `pii_unmask_log` are append-only and keep the bare identity id, which resolves to no one afterwards (08-deployment-and-ops.md, DPDP obligations). T18 scans every other table.
> - **Data request:** not an export row. It opens a `privacy_requests` row; the Owner's `POST /api/privacy-requests/{id}/report` builds the JSON on the spot, logged in `pii_unmask_log`, and nothing is stored. Resolving it empties the stored customer id, email and phone.
> - **Shop redact:** an open request and an ERROR, with the manual runbook in 08 ("Runbook: Shop redact"). Engage serves one store, so the request means the whole database, and that is never automatic.
> - **Webhooks:** `compliance_topics` added to `shopify.app.dev.toml` and `shopify.app.engage.toml`, same endpoint and HMAC (an unsigned one gets 401). They take effect when the app config is deployed. With the customer allowlist on, a request about a customer we never stored is acknowledged and dropped, which is correct: we hold nothing on them.
> - **Console:** a Privacy requests page (OWNER) with Download report and Mark done, plus "Erase this customer" at the foot of a customer's page (OWNER; reason, and typing ERASE).
> - **Tests:** `invariants.sql` T18, now 18/18 on a fresh V1–V36 database; breaking the inbox scrub fails it. `ComplianceWebhookTest` (5), `PrivacyTest` (4), UI specs. The invariants fixture needed `ON CONFLICT DO NOTHING` for `wa_v1`, which a later migration now seeds.

**The same migration (privacy part)**: `erase_identity(p_identity UUID, p_request_ref TEXT)`:
1. Writes an anonymised proof row for every consent withdrawal (channel, purpose, time, `request_ref`) into `consent_erasure_proofs`.
2. Deletes `identity_keys`, `devices`, `profiles`, `stock_waitlist`, `cascade_runs`, `events` for the identity, and nulls the identity on `orders`, `checkouts`, `carts`, `payment_attempts`.
3. Scrubs `sends.decision` of any address and sets `sends.identity_id` to a tombstone identity so aggregates still add up.
4. Deletes the identity. Writes an `audit_log` row.

`consent_erasure_proofs (request_ref, channel, purpose, state, occurred_at)` is created in that migration alongside the function, with no identity column. Most child tables already cascade on identity delete (V1–V4 FKs); the function handles the ones that do not. An `invariants.sql` test (T18, which changes the final line to `ALL 18 …`) asserts that no row anywhere still references the erased id or its phone/email.

**Shopify compliance webhooks:** `customers/redact` → `erase_identity` for the identities resolved from the payload's customer id, email and phone; `customers/data_request` → an export row for OWNER review; `shop/redact` → alert and runbook (it only arrives 48 h after uninstall). All three go through the existing HMAC + inbox path.

**Admin:** `POST /customers/{id}/erase` (OWNER, reason required).

**Done when:** T18 passes and a DPDP erasure drill on staging completes with its audit trail.

---

### ☐ P7-T06 — Security review + abuse

- Replace the in-memory rate limiter (P2-T01) with a Postgres-backed one only if pods > 2; otherwise document why in-memory is enough.
- `stock_waitlist` ≤ 50 rows per identity (a check in the handler).
- Fuzz every proxy and pixel endpoint with malformed bodies (a Jazzer or property-based test run): no 5xx, no stack traces in responses.
- External pen test scope: admin API auth and token rotation, App Proxy identity binding, session-token verification, unsubscribe tokens.
- Rotate before launch: Meta system-user token, Shopify API secret, Razorpay webhook secret, Firebase service account, admin JWT key pair.
- Backup restore rehearsed from a production snapshot into a scratch instance; timing recorded.

**Done when:** pen-test findings rated high or critical are fixed and re-tested.

---

### ☐ P7-T07 — Production deployment

Per `08-deployment-and-ops.md`:
- Images for `ingest-api`, `worker`, `admin-api`; `admin-ui` as static files behind the same domain as `admin-api`.
- Flyway as a Kubernetes Job before rollout (`FLYWAY_ON_STARTUP=false` in every app). Migrations are forward-only.
- HPA: `ingest-api` on CPU and request rate (min 2); `worker` on inbox and cascade backlog metrics (min 2, max 20); `admin-api` fixed at 2.
- Scheduled jobs use advisory locks, so running several worker pods is safe.
- Secrets from the secret manager. Postgres in `ap-south-1` with PITR.

**Done when:** a rolling deploy on production with live webhook traffic shows zero Shopify webhook retries.

---

### ☐ P7-T08 — Go-live

Work through the phase-7 §4 checklist in production, signed by name and date in `docs/qa/go-live.md`. Then:
1. Day 0: transactional only (order tracking, payment failed, NDR, fit check).
2. Day 3: push marketing journeys (back-in-stock, price drop, browse, cart step 1).
3. Day 7: WhatsApp marketing journeys, with the daily budget set explicitly (not the default).
4. Day 14: email warm-up begins.
5. Day 28: first lift report reviewed. Journeys with no measurable lift are switched off.

---

## Exit gate

- [ ] Go-live checklist signed
- [ ] 28 days in production with no Meta quality drop below GREEN
- [ ] First lift report reviewed and journey decisions recorded

---

## Appendix — native apps

When Android or iOS apps exist, follow the phase-7 appendix: no new channel, new `devices.platform` values (`ANDROID`, `IOS_APP`) already allowed by V3, `ApnsConfig` in `FcmAdapter`, and deep links on the same URLs. Plan it as its own phase with a register endpoint authenticated by the app's customer session rather than the App Proxy.
