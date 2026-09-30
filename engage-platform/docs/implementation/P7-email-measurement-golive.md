# P7 — Email, measurement, go-live · Implementation

**Weeks 11–12 · Owners: BE2 (email, deploy), BE1 (measurement, privacy), FE (reports) · Design:** [`technical/phase-7-email-measurement-golive.md`](../technical/phase-7-email-measurement-golive.md), [`08-deployment-and-ops.md`](../08-deployment-and-ops.md)

## Outcome

Marketing email takes the free second and third touches in cascades. Every journey reports incremental revenue against its holdout. The system has survived a 10× load test, a security review and an erasure drill, and the go-live checklist is signed.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P7-T01 | `SesAdapter` + SNS bounce/complaint intake + one-click unsubscribe | BE2 | 2 d | P0-T07 |
| P7-T02 | MJML templates + warm-up ramp | FE + BE2 | 1.5 d | T01 |
| P7-T03 | Measurement: V18 lift views, reports, spend reconciliation | BE1 + FE | 2 d | P5-T09 |
| P7-T04 | Load test (k6) | BE2 | 1.5 d | P6 done |
| P7-T05 | Privacy: `erase_identity()`, Shopify compliance webhooks | BE1 | 1.5 d | P1 done |
| P7-T06 | Security review + abuse hardening | BE1 + BE2 | 2 d | P6 done |
| P7-T07 | Production deployment | BE2 | 1.5 d | T04, T06 |
| P7-T08 | Go-live | All | 1 d | everything |

---

### ☐ P7-T01 — Email channel

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

### ☐ P7-T02 — Templates + warm-up

- `templates/email/*.mjml` compiled with `mjml` at build time into `templates/email/dist/*.html`, plus a `.txt` alternative for each. The lint (P3-T03) fails a build whose HTML exceeds 100 KB or lacks the unsubscribe placeholder.
- Warm-up: config key `email.daily_cap` (added in V18) with **effective-dated versions**, entered by an operator through the P6 config API (so each has an actor and a reason), for weeks 1–4 (500, 2,000, 8,000, then removed). The policy engine counts today's email marketing sends against it and defers the rest to tomorrow 09:00 IST.
- Audience for weeks 1–3 is further restricted by an `email_engaged_days` guard (opened or clicked in 30/60 days, or purchased in 180).

**Done when:** four `config_versions` rows exist for `email.daily_cap` with consecutive effective windows, and a test shows the 501st email on day 1 deferred.

---

### ☐ P7-T03 — Measurement

**Migration `V18__measurement_and_privacy.sql`** (lift views part)
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

### ☐ P7-T05 — Privacy

**V18 (privacy part)**: `erase_identity(p_identity UUID, p_request_ref TEXT)`:
1. Writes an anonymised proof row for every consent withdrawal (channel, purpose, time, `request_ref`) into `consent_erasure_proofs`.
2. Deletes `identity_keys`, `devices`, `profiles`, `stock_waitlist`, `cascade_runs`, `events` for the identity, and nulls the identity on `orders`, `checkouts`, `carts`, `payment_attempts`.
3. Scrubs `sends.decision` of any address and sets `sends.identity_id` to a tombstone identity so aggregates still add up.
4. Deletes the identity. Writes an `audit_log` row.

`consent_erasure_proofs (request_ref, channel, purpose, state, occurred_at)` is created in V18 alongside the function, with no identity column. Most child tables already cascade on identity delete (V1–V4 FKs); the function handles the ones that do not. An `invariants.sql` test (T18, which changes the final line to `ALL 18 …`) asserts that no row anywhere still references the erased id or its phone/email.

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
