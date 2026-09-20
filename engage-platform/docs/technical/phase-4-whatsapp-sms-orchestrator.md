# Phase 4 — WhatsApp, SMS fallback and the orchestrator

**Weeks 5–7 · Owner: backend · Depends on: Phase 3; Meta verification and utility templates approved; DLT header and templates approved**

## Goal

Add WhatsApp and SMS behind the existing door, learn which numbers can receive WhatsApp without losing real customers to a false negative, and turn single-step intents into multi-channel cascades. The first customer-facing WhatsApp traffic is **order tracking**: utility-only, opt-in-backed, and the cheapest way to learn WhatsApp capability across the customer base.

## Scope

**In:** WhatsApp Cloud API adapter, template registry sync, status and inbound webhooks, quality monitoring, STOP handling, `CapabilityService`, SMS (DLT) adapter, `CascadeRunner`, multi-step cascades, the `order_tracking` journey.
**Out:** marketing WhatsApp journeys (Phase 5), campaigns (Phase 6).

The cascade model, per-channel success criteria, capability rules and SMS fallback triggers are specified in `CLAUDE.md` §3–5. This phase implements them. The rules are not restated here; the implementation detail is.

---

## 1. WhatsApp Cloud API adapter

```java
@Singleton
@Named("whatsapp")
public class WhatsAppCloudAdapter implements ChannelAdapter {

    @Override public Channel channel() { return Channel.WHATSAPP; }

    @Override
    public DispatchResult send(Decision.Allow allowed, RenderedMessage msg) throws ChannelException {
        var to = allowed.addresses().waId();                     // 91XXXXXXXXXX, no plus
        var body = allowed.freeWindow() && allowed.effectiveCategory() == Category.SERVICE
            ? WaPayloads.text(to, msg.text())                    // inside a customer-opened window
            : WaPayloads.template(to, allowed.template().providerName(),
                                  msg.locale(), msg.bodyParams(), msg.buttonParams());
        try {
            var res = client.send(config.phoneNumberId(), body);
            return DispatchResult.of(res.messages().getFirst().id(), 1);
        } catch (HttpClientResponseException e) {
            var err = WaError.parse(e);
            // Classified here, acted on by the router + CapabilityService.
            throw err.permanent()
                ? new PermanentChannelException(err.title(), err.code())
                : new TransientChannelException(err.title(), e);
        }
    }
}
```

```java
@Client("https://graph.facebook.com/${wa.graph-version}")
@Header(name = "Authorization", value = "Bearer ${wa.access-token}")
@Retryable(attempts = "3", delay = "1s", multiplier = "2.0",
           includes = TransientChannelException.class)
@CircuitBreaker(reset = "30s", attempts = "5")
public interface WaGraphClient {
    @Post("/{phoneNumberId}/messages")
    WaSendResponse send(@PathVariable String phoneNumberId, @Body WaSendRequest body);
}
```

Pin `wa.graph-version` to the current Graph API version and upgrade deliberately. Meta retires old versions on a published schedule.

**Never retry these.** 131026, 131047 (outside the service window), 131049 (Meta chose not to deliver), 132000–132015 (template problems). Retrying a 131049 is exactly the behaviour Meta's throttle is designed to penalise.

---

## 2. Template registry sync

Templates are authored in code (`templates/whatsapp/*.yaml`), submitted through the Graph API, and their Meta status is mirrored locally. The policy engine only allows a template whose local status is `APPROVED` and whose quality is not `LOW`.

```yaml
# templates/whatsapp/order_shipped.yaml
key: order_shipped
name: order_shipped_v1
category: UTILITY
languages:
  en:    "Order {{1}} is on its way with {{2}} (AWB {{3}}). Arriving {{4}}."
  hi_EN: "Aapka order {{1}} {{2}} se nikal chuka hai (AWB {{3}}). {{4}} tak pahunchega."
buttons:
  - type: URL
    text: Track order
    url: "https://brand.in/apps/track/{{1}}"
variables: [order_no, courier, awb, eta]
```

```sql
CREATE TABLE wa_templates (
  key            TEXT NOT NULL,
  language       TEXT NOT NULL,
  provider_name  TEXT NOT NULL,
  category       TEXT NOT NULL,                  -- as Meta approved it, not as we asked
  status         TEXT NOT NULL,                  -- PENDING | APPROVED | REJECTED | PAUSED | DISABLED
  quality        TEXT,                           -- GREEN | YELLOW | RED | UNKNOWN
  rejected_reason TEXT,
  synced_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, language)
);
```

**Store the category Meta approved, not the one you requested.** Meta re-classifies templates. A utility template silently approved as marketing costs about seven times more per send. The sync job alerts when the approved category differs from the requested one, before a single message goes out on it.

The `message_template_status_update` and `message_template_quality_update` webhook fields keep this table current between syncs. A template that drops to RED quality is paused locally the moment the webhook arrives.

**Hindi code in Meta's language list.** Hinglish is written in Latin script, and Meta has no separate Hinglish language code. Submit Hinglish copy under the Hindi or English language code as your approval process allows, and record the choice in the YAML. Test that the language you send matches what was approved, because a mismatch returns a 132xxx error.

---

## 3. WhatsApp webhooks

One endpoint receives every field. Verify `X-Hub-Signature-256` over the **raw body**, then dedupe on the message or status id.

| Field | Handling |
|---|---|
| `messages` → `statuses[]` | Update the `sends` row; book spend from `pricing.category` and `billable`; feed `CapabilityService.observe()`; advance or terminate the cascade (read, replied) |
| `messages` → `messages[]` | Inbound: open the 24h service window, route quick replies to events, handle STOP |
| `message_template_status_update` | Update `wa_templates.status`; alert on rejection |
| `message_template_quality_update` | Update quality; auto-pause at RED |
| `phone_number_quality_update` | Update number quality and messaging tier; page on any drop |

**Spend is booked from the status webhook, not at send time.** `pricing.category` is what Meta actually billed, and it is authoritative. It diverges from what you sent when Meta re-classifies. `billable: false` messages cost nothing, and customer service window replies are free until 1 October 2026.

### STOP handling

```java
private static final Pattern STOP = Pattern.compile(
    "^\\s*(stop|unsubscribe|opt\\s?out|band karo|mat bhejo|nahi chahiye|रुको|बंद करो)\\s*$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

void onInbound(WaInbound m) {
    var id = identity.resolveVerified(Key.waId(m.from()), Key.phone(m.from()));
    profiles.openServiceWindow(id, m.timestamp());
    capability.markCapable(id, Channel.WHATSAPP);          // they messaged us: definitely on WhatsApp

    if (STOP.matcher(m.text()).matches()) {
        consent.withdrawAllMarketing(id, "wa_stop_reply", Evidence.ofMessage(m));
        consent.withdraw(id, Channel.WHATSAPP, Purpose.TRANSACTIONAL, "wa_stop_reply");
        // Confirm once, inside the window it just opened. Free, and it stops
        // them reaching for "Block", which is what hurts the quality rating.
        router.reply(id, "You won't get further messages from BRAND here. Reply START to resume.");
        return;
    }
    events.record(id, quickReplyEvent(m).orElse("wa_message_received"), m);
}
```

STOP on WhatsApp withdraws WhatsApp entirely, both marketing and transactional, and withdraws marketing on every other channel. Order updates can still reach that customer by SMS and by Shopify's own email.

---

## 4. Capability service

Implements `CLAUDE.md` §4 exactly. The tri-state, the strike rule and the exemptions are specified there. Implementation notes:

```java
@Singleton
public class CapabilityService {

    /** Called from the WhatsApp status webhook and from inbound messages. */
    @Transactional
    public void observe(UUID identityId, DeliveryOutcome o) { /* CLAUDE.md §4.2 */ }

    @Scheduled(cron = "0 30 3 * * *", zoneId = "Asia/Kolkata")   // 03:30 IST daily
    void expireIncapable() {
        repo.resetExpired(Channel.WHATSAPP);   // INCAPABLE with recheck_after < now() -> UNKNOWN
    }
}
```

Status webhooks for the same number can arrive concurrently on different pods. The strike update is a locked read-modify-write, so two failures cannot both count as "the first strike today":

```java
@Transactional
void strike(UUID identityId, DeliveryOutcome o) {
    var day = LocalDate.ofInstant(o.at(), IST);
    repo.ensureRow(identityId, Channel.WHATSAPP);                 // INSERT ... ON CONFLICT DO NOTHING
    var cap = repo.lockForUpdate(identityId, Channel.WHATSAPP);   // SELECT ... FOR UPDATE

    if (cap.state() == CAPABLE) return;                           // proof of delivery beats strikes
    if (cap.strikeDays().contains(day)) return;                   // one incident = one signal

    var next = cap.withStrike(day, o.code());
    if (next.strikeDays().size() >= 2) {
        next = next.incapable(clock.instant().plus(Duration.ofDays(120)));
    }
    repo.save(next);
}
```

Keep this logic in Java rather than in a single SQL upsert. The natural SQL form uses Postgres's jsonb `?` operator, and `?` is JDBC's parameter marker, so Micronaut Data misparses the query at runtime. Where a jsonb key test is unavoidable in SQL, use `jsonb_exists(col, :key)`.

A number that has ever delivered stays `CAPABLE`. Proof of delivery outranks a later strike, because a later 131026 on a proven number is far more likely to mean "outdated app" or "blocked us" than "no WhatsApp". A block is handled by suppression, not by capability.

---

## 5. SMS adapter (DLT)

```java
@Singleton
@Named("sms")
public class Msg91Adapter implements ChannelAdapter {

    @Override
    public DispatchResult send(Decision.Allow allowed, RenderedMessage msg) throws ChannelException {
        var t = (SmsTemplate) allowed.template();
        // The operator silently drops content that deviates from the DLT-registered
        // template. The provider still returns success. Check variable arity here,
        // because nothing downstream will tell you.
        if (msg.smsVariables().size() != t.dltVariableCount()) {
            throw new PermanentChannelException("dlt_variable_mismatch", 0);
        }
        var res = client.flow(new FlowRequest(t.providerFlowId(), config.senderId(),
                                              List.of(msg.recipient(allowed.addresses().phone()))));
        return DispatchResult.of(res.requestId(), 1);
    }
}
```

At startup, fail if any SMS template lacks a DLT template id, and log the registered sender header. SMS delivery reports arrive by webhook and update `sends.delivered_at`. For SMS, delivery **is** success, because no read signal exists (`CLAUDE.md` §3.3).

SMS templates in this build are all service/transactional: `sms_order_shipped`, `sms_out_for_delivery`, `sms_ndr`, `sms_payment_link`. Promotional SMS is not built. DND registration blocks it for a large share of Indian numbers, and the ROI does not justify the DLT promotional-scrubbing overhead.

---

## 6. Cascade runner

Implements `CLAUDE.md` §3.4. Worker loop:

```java
@Scheduled(fixedDelay = "10s")
void tick() {
    List<CascadeRun> due;
    do {
        due = runs.claimDue(200);                   // FOR UPDATE SKIP LOCKED
        due.forEach(this::advanceSafely);
    } while (due.size() == 200);
}

void advance(CascadeRun run) {
    var def = definitions.require(run.intentKey());
    var step = def.step(run.stepIndex());
    if (step == null) { runs.finish(run.id(), "exhausted"); return; }

    var ctx = GuardContext.load(run, capability, profiles, carts);
    if (!step.guards().allMatch(ctx)) {             // skip without consuming the wait
        attempts.record(run, step, "skipped", step.firstFailingGuard(ctx));
        runs.moveTo(run.id(), run.stepIndex() + 1, clock.instant());
        return;
    }

    var result = router.send(SendCommand.forCascade(run, step));
    attempts.record(run, step, result);

    switch (result) {
        case SendResult.Deferred d when run.deferrals() < 3 -> runs.defer(run.id(), d.until());
        case SendResult.Deferred d -> runs.moveTo(run.id(), run.stepIndex() + 1, clock.instant());
        // 131026 / 131049: escalate now, do not wait out the step's timer.
        case SendResult.Failed f when f.escalateImmediately() ->
            runs.moveTo(run.id(), run.stepIndex() + 1, clock.instant());
        default -> runs.moveTo(run.id(), run.stepIndex() + 1,
                               clock.instant().plus(step.waitAfter()));
    }
}
```

**Terminating early.** Success signals (push click beacon, WhatsApp read or reply, SMS delivered, email click) and cancel events (`order_placed`, `cart_emptied`) are handled in the webhook paths. They call `orchestrator.onSignal(identityId, subjectKey, signal)`, which moves matching live runs to `succeeded` or `cancelled` in one `UPDATE`. A conversion must stop further messages within one tick, not at the next scheduled step.

---

## 7. First WhatsApp journey: `order_tracking`

Utility only. Sent only to customers with a WhatsApp opt-in from Phase 2 §6.

| Shopify event | Template | Fallback if WhatsApp can't deliver |
|---|---|---|
| `orders/create` + cart opt-in | `order_confirmed_v1` | none (Shopify emails the order) |
| `fulfillments/create` | `order_shipped_v1` | `sms_order_shipped` |
| courier: out for delivery | `out_for_delivery_v1` | `sms_out_for_delivery` |
| courier: delivered | none; starts `post_purchase_fit` in Phase 5 | — |
| courier: NDR | `ndr_raised_v1` | `sms_ndr` |
| `refunds/create` | `refund_initiated_v1` | none (Shopify emails it) |

Shipping and out-for-delivery events that go through Shopify's own notifications can come in by SMS too. **Turn off Shopify's SMS shipping notifications** once this journey is live, or customers get two texts for every parcel. Keep Shopify's emails: they are the receipt of record.

This journey is also the capability-discovery engine. Every order from an opted-in customer produces at least one utility send. Within a few weeks, most of your active customers have a known WhatsApp state, and marketing cascades in Phase 5 inherit it.

---

## Acceptance criteria

All capability and cascade tests listed in `CLAUDE.md` §10 pass on Testcontainers. In addition:

- [ ] Template sync alerts when Meta approves a template in a different category than requested
- [ ] A RED quality webhook pauses the template before the next send
- [ ] A status webhook with `pricing.category=marketing` for a template we sent as utility books marketing spend, and it raises an alert
- [ ] Inbound "band karo" withdraws WhatsApp entirely and marketing everywhere, and sends exactly one confirmation
- [ ] `order_shipped` to an `INCAPABLE` identity goes straight to SMS, with no WhatsApp attempt
- [ ] An SMS with the wrong variable count is rejected before calling the provider
- [ ] `order_tracking` runs end-to-end on the development store: order → shipped → out for delivery, all utility, all under the policy engine

## Exit gate

`order_tracking` live for 7 days. Quality rating HIGH (GREEN) on the number. The capability distribution (`CAPABLE` / `UNKNOWN` / `INCAPABLE`) is reported daily and the `UNKNOWN` share is falling.
