# Engage — unified messaging orchestrator

Build spec for Claude Code. Read this file fully before writing any code.

**Mission:** one system that decides *whether*, *how*, and *when* to reach a customer across WhatsApp, push (web/Android/iOS), SMS and email — with automatic fallback when a channel cannot deliver.

**Stack:** Micronaut 5 · Java 25 · Postgres 16 · Angular 22 admin console.

**Storefront:** Shopify (standard checkout, non-Plus). **Business model:** prepaid only, no COD. **Payment gateway:** Razorpay.

**Build status:** Phase 1 is implemented (`core-domain/`, `ingest-api/`, `db/migration/V1–V6`). To run locally, follow `LOCAL-SETUP.md`. Where Phase 1 code and a design doc disagree, the code and its tests win. Before changing identity resolution or consent writing, read the comments in `V6__identity_functions.sql` and `ConsentWriter.java`: both encode compliance decisions that tests pin down.

Companion documents:
- `docs/technical/` — **the phased build plan. Work phase by phase; each phase doc has acceptance criteria and an exit gate.** Start at `docs/technical/00-overview.md`.
- `INDIA-PLAYBOOK.md` — the intent catalogue, copy, and the consumer-psychology reasoning behind each journey.

This file is the engineering contract. Where a phase doc and this file disagree, this file's invariants win and the phase doc is the bug.

---

## 1. Non-negotiable invariants

Violating any of these is a bug, not a tradeoff. If a task seems to require breaking one, stop and flag it.

1. **One door.** Nothing sends except through `MessageOrchestrator.dispatch()`. No controller, journey, campaign executor or script calls a channel adapter directly.
2. **Intents, not channels.** Callers say *what they want to achieve* (`cart_recovery`), never *which channel to use*. The orchestrator picks.
3. **Blocked and failed attempts are persisted** with a reason. They are the audit trail and the analytics denominator.
4. **Config that governs sends is append-only and versioned.** Every send records the config snapshot that permitted it.
5. **A deferral is not a refusal.** Quiet hours and budget exhaustion reschedule; they never silently drop a message.
6. **Money is `long` paise.** Never `double`, never `float`.
7. **Times are stored UTC, reasoned in IST.** `Asia/Kolkata` is the business timezone.
8. **WhatsApp requires an opt-in for every business-initiated message, utility included.** Implied transactional consent applies to SMS and email only. Marketing on any channel needs an explicit grant whose registered copy declares the marketing purpose.
9. **Storefront identity comes only from signed sources.** Customer identity from the Shopify storefront comes from the App Proxy's signed `logged_in_customer_id` or from a verified checkout session token, never from a request body.
10. **Consent is stamped with when the person chose, never with processing time.** `consent_current` resolves by `occurred_at`. Stamping with `now()` lets a late or retried webhook re-subscribe someone who unsubscribed, or re-grant WhatsApp after a STOP. Every consent statement also carries a unique reference, so a replay writes nothing.
11. **Webhook handlers are idempotent and order-independent.** Providers retry, and pods race. Whichever of a checkout, order, cart or payment arrives second must find and link the other. Test every handler in natural, reversed and replayed order.

---

## 2. India channel reality

This table drives every design decision below. Do not treat the four channels as interchangeable.

| | WhatsApp | Push | SMS | Email |
|---|---|---|---|---|
| Reach (India) | ~95% of smartphone users | Android ~95% of devices; **iOS web push ~0%** | ~100% | ~100% addresses, ~10–15% attention |
| Open rate | 85–95% | 5–15% | 20–40% | 8–15% |
| Marginal cost | ₹0.86 marketing / ₹0.115 utility | **₹0** | ₹0.15–0.25 | **~₹0** |
| Read receipt | Yes | Click only | **No** — delivery only | Open (unreliable) |
| Two-way | Yes | No | Practically no | No |
| Regulator | Meta policy | Platform | **TRAI DLT mandatory** | DPDP + spam filters |

**Consequences to encode:**

- Push and email are free at the margin → they are the **volume** channels.
- WhatsApp costs real money and carries quality-rating risk → it is the **precision** channel, gated on value.
- SMS exists for two jobs only: reaching numbers WhatsApp cannot, and delivery-critical alerts.
- **iOS cannot receive web push.** Safari still requires the site be installed to the Home Screen, which effectively nobody does on a storefront. For iOS users without a native app, **WhatsApp is the push channel.** Encode this — do not schedule a push step for an iOS-web identity and count it as an attempt.

---

## 3. The orchestrator

### 3.1 Public API — the only door

```java
public interface MessageOrchestrator {
    /** Idempotent on (intentKey, subjectKey, identityId). */
    CascadeRun dispatch(MessageIntent intent);

    /** Terminate in-flight cascades for a subject. Called on conversion. */
    void cancel(UUID identityId, String subjectKey, String reason);
}

public record MessageIntent(
    UUID identityId,
    String intentKey,          // "cart_recovery" — resolves to a CascadeDefinition
    String subjectKey,         // cart token / order id / variant id — dedupe + cancel key
    Map<String, Object> vars,  // template variables
    Instant notBefore,
    Priority priority          // TRANSACTIONAL | ENGAGEMENT | PROMOTIONAL
) {}
```

Journeys, campaigns and webhooks all call `dispatch()`. That is what "handle it in one place" means concretely.

### 3.2 Cascade definitions

A cascade is an ordered list of attempts with escalation timing and guards. **Definitions are code** (reviewed, versioned); **parameters are config** (waits, thresholds, enable flags — editable in the admin UI with an audit trail).

```java
CascadeDefinition.of("cart_recovery")
    .priority(PROMOTIONAL)
    .succeedsOn(CLICKED, CONVERTED)             // stops the cascade
    .cancelsOn("order_placed", "cart_emptied")  // external events kill it
    .step(push("push_cart")
            .require(HAS_LIVE_DEVICE)
            .waitAfter(Duration.ofHours(4)))
    .step(email("email_cart")
            .require(HAS_EMAIL_MARKETING)
            .waitAfter(Duration.ofHours(14)))
    .step(whatsapp("cart_recovery")
            .require(WA_CAPABLE, WA_MARKETING, cartValueAtLeast(1_500_00)))  // ₹1,500 in paise
    .build();
```

There is no SMS step. Promotional SMS is not built: DND registration blocks it for a large share of Indian numbers, and the ROI does not justify DLT promotional scrubbing. SMS appears only in transactional cascades (`order_tracking`, `ndr_rescue`, `payment_failed`).

**Paise literals.** ₹1,500 is `1_500_00` (150,000 paise). Write rupee thresholds with the underscore before the last two digits so the paise are visible. `150_00` is ₹150, and that mistake is easy to make and hard to spot in review.

Rules the runtime must enforce:

- **A step whose guards fail is skipped, not failed.** Move to the next step immediately; do not consume the wait.
- **`waitAfter` is the delay before the *next* step**, measured from this step's send time.
- **Success terminates the whole cascade**, not just the current step.
- **Every step still passes through the policy engine.** Guards are cascade-level; consent, caps, quiet hours and budget are policy-level and are never bypassed.
- **A `Defer` from policy reschedules the same step.** Cap the deferrals at 3, then skip to the next step.

### 3.3 Per-channel success criteria

Success is channel-specific and intent-specific. Getting this wrong makes the cascade either spam or give up early.

| Channel | Signal available | Counts as success |
|---|---|---|
| Push | delivered, clicked | **clicked** only |
| WhatsApp | sent, delivered, read, replied, button-clicked | **read** for transactional; **clicked or replied** for promotional |
| SMS | delivered (no read receipt exists) | **delivered** |
| Email | delivered, opened, clicked | **clicked** (opens are unreliable post-MPP) |

For `ndr_rescue` and `address_confirm` the only success is an actual **reply** or button tap. A read receipt is not a confirmation — someone who read the message and did nothing has not rescheduled their delivery.

### 3.4 Runtime

```sql
CREATE TABLE cascade_runs (
  id            BIGSERIAL PRIMARY KEY,
  intent_key    TEXT NOT NULL,
  identity_id   UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  subject_key   TEXT NOT NULL,
  step_index    INT  NOT NULL DEFAULT 0,
  vars          JSONB NOT NULL DEFAULT '{}',
  status        TEXT NOT NULL DEFAULT 'active'
                CHECK (status IN ('active','waiting','succeeded','exhausted','cancelled','failed')),
  outcome       TEXT,
  next_step_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  deferrals     SMALLINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One live cascade per intent per subject. This is the dedupe guarantee.
CREATE UNIQUE INDEX ON cascade_runs (intent_key, subject_key)
  WHERE status IN ('active','waiting');
CREATE INDEX ON cascade_runs (status, next_step_at) WHERE status IN ('active','waiting');

CREATE TABLE cascade_attempts (
  id           BIGSERIAL PRIMARY KEY,
  run_id       BIGINT NOT NULL REFERENCES cascade_runs(id) ON DELETE CASCADE,
  step_index   INT NOT NULL,
  channel      channel NOT NULL,
  send_id      BIGINT REFERENCES sends(id),
  result       TEXT NOT NULL,   -- sent|skipped|blocked|deferred|failed
  reason       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Worker claims due runs with `FOR UPDATE SKIP LOCKED`, same pattern as the journey ticker. Engagement webhooks (WhatsApp read receipts, push click callbacks, email click tracking) update the run and may terminate it early.

---

## 4. WhatsApp capability detection — read this carefully

**There is no way to check whether a number has WhatsApp.** The Cloud API has no capability endpoint; the old On-Premises `/contacts` endpoint is gone. Any library or blog claiming otherwise is wrong. You learn capability by attempting and observing.

### 4.1 The trap

Error **131026 "Message undeliverable" is a catch-all**, not a clean "no WhatsApp" signal. Its documented causes include: number not on WhatsApp, user hasn't accepted current WhatsApp ToS, outdated WhatsApp version, **authentication templates sent to Indian (+91) numbers**, no opt-in, expired service window, template mismatch, user blocked the business, and account-level restrictions.

A naive implementation marks the number dead on the first 131026 and permanently loses customers whose WhatsApp was merely out of date. Do not do that.

### 4.2 Tri-state capability with corroboration

```sql
CREATE TABLE channel_capability (
  identity_id  UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  channel      channel NOT NULL,
  state        TEXT NOT NULL DEFAULT 'UNKNOWN'
               CHECK (state IN ('UNKNOWN','CAPABLE','INCAPABLE')),
  evidence     JSONB NOT NULL DEFAULT '{}',   -- error codes + timestamps seen
  strikes      SMALLINT NOT NULL DEFAULT 0,
  decided_at   TIMESTAMPTZ,
  recheck_after TIMESTAMPTZ,
  PRIMARY KEY (identity_id, channel)
);
```

Learning rules — implement exactly:

```java
void observe(UUID identityId, Channel channel, DeliveryOutcome outcome) {
    switch (outcome.type()) {

        // Any successful delivery is proof. Promote immediately and permanently.
        case DELIVERED, READ, REPLIED ->
            capability.markCapable(identityId, channel);

        case FAILED -> {
            int code = outcome.providerCode();

            // India-specific: auth templates to +91 fail with 131026 for
            // regulatory reasons that say NOTHING about whether the number
            // has WhatsApp. Never let this influence capability.
            if (code == 131026 && outcome.category() == AUTHENTICATION
                    && outcome.msisdn().startsWith("91")) {
                return;
            }

            // 131049: Meta chose not to deliver for ecosystem health. The
            // number is fine; Meta throttled us. Fall back for THIS message,
            // but do not touch capability, and do not retry for 24h.
            if (code == 131049) {
                capability.backoff(identityId, channel, Duration.ofHours(24));
                return;
            }

            // 131026 on a UTILITY template to an opted-in number is the
            // strongest available signal. Two strikes on different days
            // before we write the number off.
            if (code == 131026 && outcome.category() == UTILITY) {
                capability.strike(identityId, channel, outcome);
                return;
            }

            // Everything else is noise for capability purposes.
        }
    }
}
```

`strike()` increments the counter only if the last strike was on a **different calendar day** — a burst of failures inside one incident is one signal, not five. At **2 strikes** the state becomes `INCAPABLE` with `recheck_after = now() + 120 days`. People do install WhatsApp; a permanent write-off is wrong.

### 4.3 Where capability is learned for free

**Discover capability during the transactional lifecycle, not during marketing.**

Every customer who completes an order receives an order confirmation — a utility template at ₹0.115. That send tells you definitively whether the number has WhatsApp, for about twelve paise. By the time you want to send them a ₹0.86 marketing template, you already know.

Never spend a marketing template to discover capability. Encode this as a guard:

```java
// Promotional cascades require KNOWN capability. Transactional cascades
// attempt optimistically on UNKNOWN, because that attempt is the discovery
// mechanism and it costs almost nothing.
Guard WA_CAPABLE = ctx -> switch (ctx.capability(WHATSAPP)) {
    case CAPABLE -> true;
    case INCAPABLE -> false;
    case UNKNOWN -> ctx.priority() == TRANSACTIONAL
                 || ctx.cartValuePaise() >= 2_500_00;  // ₹2,500 — see below
};
```

**The prepaid-only gap.** On a COD store, an order is placed by low-intent browsers too, so you learn capability early and cheaply for a wide population. Prepaid-only removes that: your only free discovery event is a *completed payment*, which means first-time cart and checkout abandoners sit at `UNKNOWN` — exactly the people you most want to reach.

Two mitigations, in priority order:

1. **Capture the opt-in before checkout, as a cart attribute.** Non-Plus stores cannot add fields to the checkout steps. Instead, an unticked "Send me order updates and offers from BRAND on WhatsApp" checkbox in the cart writes a cart attribute. Shopify carries it into the order's `note_attributes`, and the phone number comes from checkout. See `docs/technical/phase-2-shopify-web-push.md` §6.1. A Thank you page extension catches everyone who skipped it (§6.2).
2. **Accept the discovery cost on valuable carts only.** Above ₹2,500 the ₹0.86 is worth spending to learn, so the guard lets `UNKNOWN` through on value. Below that, push and email carry the cascade. Campaigns never do this; they require `CAPABLE`.

### 4.4 SMS fallback triggers

Three distinct conditions, three behaviours:

| Condition | Behaviour |
|---|---|
| `INCAPABLE` known before sending | Skip WhatsApp entirely, go to the SMS step. No wasted attempt. |
| Send failed 131026 | Escalate to SMS immediately — do not wait out the timer. |
| Send failed 131049 (Meta throttle) | Escalate to SMS immediately; back off WhatsApp 24h; capability untouched. |
| Sent but unread after `waitAfter` | Normal cascade progression. |

---

## 5. SMS: DLT is not optional

TRAI's TCCCPR requires DLT registration for SMS in India: a registered entity id, a registered 6-character sender header, and a **pre-registered template whose variable slots match exactly**.

**Content that deviates from the registered template is dropped by the telecom operator, not by your provider.** You get a success response and no delivery. This is the single most common SMS integration failure.

Therefore:

```java
public record SmsTemplate(
    String key,
    String dltTemplateId,      // mandatory — fail at startup if missing
    String body,               // must match the registered text exactly
    List<String> variables
) {}
```

Validate at application startup that every SMS template has a `dltTemplateId`, and validate on every send that the rendered variable count matches the registered slot count. Fail loudly at boot rather than silently at 2am.

**DND / NDNC:** promotional SMS to DND-registered numbers is blocked by the operator, and a large share of Indian numbers are registered. Transactional and service-category SMS still lands. This is another reason SMS is a fallback and delivery-alert channel, not a marketing channel.

**WhatsApp requires no DLT registration** — it runs on Meta's Business Messaging Policy. The DPDP Act sits above both and requires real consent records either way.

---

## 6. Push: three platforms, one adapter

```java
public record Device(
    UUID identityId,
    String token,
    Platform platform,      // WEB | ANDROID | IOS_APP | IOS_WEB
    String appVersion,
    boolean active
) {}
```

**Send data-only messages on every platform.** A `notification` block hands rendering to the OS and you lose control of the title, icon and click-through URL on background messages.

```java
MulticastMessage.builder()
    .putAllData(payload)                       // data-only, no notification block
    .setAndroidConfig(AndroidConfig.builder()
        .setTtl(ttlSeconds * 1000)
        .setPriority(AndroidConfig.Priority.HIGH)
        .build())
    .setApnsConfig(ApnsConfig.builder()
        .putHeader("apns-priority", "10")
        .putHeader("apns-push-type", "alert")
        .setAps(Aps.builder().setContentAvailable(true).build())
        .build())
    .setWebpushConfig(WebpushConfig.builder()
        .putHeader("TTL", String.valueOf(ttlSeconds))
        .putHeader("Urgency", urgency)
        .build())
    .addAllTokens(tokens)
    .build();
```

**Platform rules to encode:**

- `IOS_WEB` devices **cannot exist** unless the user installed the PWA to their Home Screen. Do not register a web push token on iOS Safari without that; the subscription will be silently useless. Detect standalone mode before prompting.
- `IOS_APP` requires APNs credentials uploaded to Firebase and the notification permission requested on a user gesture.
- Web and Android web push must request permission on a **user gesture after demonstrated intent**, never on page load. Chrome degrades the prompt for sites that ask immediately, and a denied permission is permanent for that origin.
- Prune dead tokens on `registration-token-not-registered` and `invalid-registration-token`. Web push tokens rot fast; an unpruned list wrecks your delivery stats and hides real problems.

**TTL matters.** A back-in-stock push is worthless after an hour; set `ttl=3600, urgency=high`. A cart nudge can live a day. Never send a 7-day TTL promotional push — it arrives stale and gets the notification permission revoked.

---

## 7. Email: honest positioning

Email conversion in India is weak for B2C — commonly 8–15% open rates versus 85–95% on WhatsApp, buried under Gmail's Promotions tab, and most consumers under 35 treat email as a place for receipts and work, not as a social channel.

**Do not make email the primary conversion channel.** It has four real jobs:

1. **Receipts and invoices** — expected, actually opened, and legally useful. Transactional email open rates are several times promotional.
2. **Long-form catalogue and editorial** — lookbooks, styling guides, care instructions. Content that needs space WhatsApp does not have.
3. **Zero-cost win-back** — you can email a lapsed customer six times for free. You cannot WhatsApp them six times at any price without destroying your quality rating.
4. **Deliverability insurance** — if your WhatsApp number gets restricted, email is the list you still own outright.

Implement it properly anyway: SPF, DKIM, DMARC, a dedicated sending subdomain, `List-Unsubscribe` with one-click (Gmail and Yahoo require it at volume), and bounce/complaint handling wired to the suppression table.

---

## 8. Module layout

Build in this structure. The dependency direction is strictly inward.

```
engage/
├─ core-domain/          entities, value objects, Paise, Channel, Category
├─ policy/               PolicyEngine, ConfigResolver, KillSwitch, holdouts
├─ orchestrator/         MessageOrchestrator, CascadeDefinition, CascadeRunner,
│                        CapabilityService          ← the "one place"
├─ channels/             ChannelAdapter + WhatsAppCloud, Fcm, Ses, Msg91
├─ journeys/             journey definitions + durable state machine
├─ ingest-api/           webhooks: Shopify, WhatsApp, courier, storefront SDK
├─ admin-api/            auth, RBAC, campaigns, config, reporting
├─ worker/               journey ticker, cascade ticker, campaign executor
└─ admin-ui/             Angular 22
```

`channels` must not import `policy` or `orchestrator`. `orchestrator` depends on both through interfaces. This is what structurally prevents someone calling WhatsApp from a controller.

---

## 9. Build phases

The design, with scope, code, acceptance criteria and exit gates, is in `docs/technical/`. The **work** is in `docs/implementation/`: one task per Claude Code session, each with its files, migration, tests and a *Done when* command. Start every session from `docs/implementation/README.md`. Do not start a phase until the previous phase's exit gate passes.

| Phase | Doc | Delivers |
|---|---|---|
| 0 | `phase-0-prerequisites.md` | Meta verification, DLT, Firebase, Shopify app, payment-signal spike, repo + CI |
| 1 | `phase-1-core-platform.md` | Schema, identity, consent ledger, Shopify webhooks, App Proxy verification |
| 2 | `phase-2-shopify-web-push.md` | Theme app extension, service worker via App Proxy, FCM tokens, opt-in capture |
| 3 | `phase-3-policy-and-push-sending.md` | Policy engine, router, FCM adapter, first push intents, ArchUnit enforcement |
| 4 | `phase-4-whatsapp-sms-orchestrator.md` | WhatsApp Cloud API, capability learning, DLT SMS, cascades, `order_tracking` |
| 5 | `phase-5-journeys.md` | Payment recovery, checkout abandon, cart recovery, fit check, NDR, win-back |
| 6 | `phase-6-admin-console-campaigns.md` | Auth, RBAC, config, consent-copy registry, dashboards, campaigns |
| 7 | `phase-7-email-measurement-golive.md` | Marketing email, holdout measurement, hardening, go-live |

**Why push comes before WhatsApp:** it has no third-party approval gate and costs nothing per message, so it is the fastest way to get real traffic through the one-door architecture. It also starts collecting subscribers from week 3 while Meta verification is still pending.

**Why `payment_failed` needs a Phase 0 spike:** it is only utility-category (~₹0.12) when triggered by a gateway-confirmed failure. A checkout that merely stalls at payment is an abandoned checkout, which Meta treats as marketing. Whether the gateway's `payment.failed` webhook fires for Shopify-initiated payments, and whether it can be joined to the checkout, must be proven with a real test payment before Phase 5 is planned.

---

## 10. Required tests

Real Postgres 16: the local `engage_test` database (tests refuse to run against a name without the `_test` suffix) and a Postgres service container in CI. Testcontainers is fine where Docker is available. **Not H2** — this code uses enum casts, intervals, partial indexes and `SKIP LOCKED`, none of which H2 models faithfully, and a green H2 suite that fails on Postgres is worse than no suite.

```java
// Policy
marketing_blocked_without_explicit_consent()
utility_allowed_without_marketing_consent()
quiet_hours_defers_and_does_not_drop()
order_critical_utility_ignores_quiet_hours()
frequency_cap_counts_across_journeys_not_within_one()
budget_guard_blocks_when_daily_spend_exceeded()
holdout_bucket_is_stable_across_calls()
stop_reply_propagates_to_all_marketing_channels()

// Capability — these are the ones that prevent silent customer loss
auth_template_131026_to_91_number_records_no_strike()
utility_template_131026_records_one_strike()
two_strikes_on_different_days_marks_incapable()
two_failures_same_day_count_as_one_strike()
successful_delivery_promotes_to_capable_immediately()
incapable_expires_after_120_days_to_unknown()
error_131049_does_not_change_capability()

// Cascade
guard_failure_skips_step_without_consuming_wait()
success_signal_terminates_remaining_steps()
cancel_event_terminates_within_one_tick()
policy_defer_reschedules_same_step_max_three_times()
incapable_whatsapp_routes_directly_to_sms()
ios_web_identity_never_gets_a_push_step()
```

---

## 11. Conventions

- Java records for DTOs; sealed interfaces for outcomes so the compiler enforces exhaustive handling
- Micronaut Data JDBC, not JPA. Write the query.
- Flyway forward-only; migrations run as a Job before rollout, never on app startup
- No secrets in the database or the admin UI — environment variables from the secret manager only
- Every admin mutation writes an `audit_log` row **in the same transaction**
- Rates (₹0.8631 marketing, ₹0.115 utility) live in config, not code. They change.

---

## 12. Sources

- [WhatsApp error 131026 — causes](https://voltade.com/learn/whatsapp/whatsapp-error-131026-message-undeliverable)
- [WhatsApp error 131049 — healthy ecosystem throttling](https://blog.campaignhq.co/whatsapp-healthy-ecosystem-error-131049)
- [iOS PWA and web push limitations, 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide)
- [iOS web push requirements](https://pushpad.xyz/blog/ios-special-requirements-for-web-push-notifications)
- [Micronaut 5 / Java 25 baseline](https://micronaut.io/2026/04/27/micronaut-framework-5-0-with-java-25-baseline/)
