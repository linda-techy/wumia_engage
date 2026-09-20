# Phase 3 — Policy engine and push sending

**Weeks 4–5 · Owner: backend · Depends on: Phase 2 tokens accumulating**

## Goal

Build the one door — orchestrator → policy → router — with a single channel behind it, and send the first real messages. The channel is FCM web push. The first intents are the ones where push alone earns its place: back-in-stock, price drop, browse abandon, and the first step of cart recovery.

WhatsApp arrives in Phase 4 as a second adapter behind the same door. Building the door around one channel first is what keeps caps, consent and quiet hours consistent across channels later.

## Scope

**In:** `PolicyEngine`, `MessageRouter`, `MessageOrchestrator` (single-step cascades), `FcmAdapter`, versioned config and snapshots, engagement beacons into `sends`, ArchUnit enforcement, template lint, four push intents.
**Out:** multi-channel cascades and capability learning (Phase 4), the admin console (Phase 6).

The policy engine design, including its sealed `Decision` type, the ordering of checks and the Testcontainers suite, is specified in [`04-backend-micronaut.md`](../04-backend-micronaut.md). This phase builds it as specified, with the corrections in §1.

---

## 1. Policy corrections carried into the build

**WhatsApp needs an opt-in for every business-initiated message.** Meta's policy requires opt-in before *any* business-initiated message, utility included. The earlier spec treated transactional messages as implicitly consented on every channel. That remains true for SMS (DLT service-implicit) and for transactional email. It is **not** true for WhatsApp. The consent check becomes:

```java
// Step 4 of decide(), replacing the single marketing check.
switch (req.channel()) {
    case WHATSAPP -> {
        // Replies inside a customer-opened service window are user-initiated.
        boolean replying = subject.inServiceWindow() && req.isReply();
        if (!replying && !subject.hasAnyGrant(WHATSAPP)) return block(NO_WHATSAPP_OPT_IN);
        if (template.category() == MARKETING && !subject.hasGrant(WHATSAPP, MARKETING))
            return block(NO_MARKETING_CONSENT);
    }
    case PUSH -> {
        // Browser permission is capability; the soft-ask grant is consent.
        if (!subject.hasGrant(PUSH, MARKETING)) return block(NO_MARKETING_CONSENT);
    }
    default -> {
        if (template.category() == MARKETING && !subject.hasGrant(req.channel(), MARKETING))
            return block(NO_MARKETING_CONSENT);
    }
}
```

Add `NO_WHATSAPP_OPT_IN` to `BlockReason`. It will be the largest block bucket for the first months, and that is correct: it measures how well Phase 2's opt-in capture is working.

**iOS web devices are never push targets.** The `devices.platform` check lives in the reachability step (step 3). An identity with only an `IOS_WEB` row is `UNREACHABLE` on push. That makes the cascade fall through to WhatsApp instead of counting a push that cannot arrive.

---

## 2. FCM adapter

Firebase Admin SDK for Java, using the HTTP v1 API. Use `sendEachForMulticast`, because the old batch-send endpoint has been retired.

```java
@Singleton
@Named("push")
public class FcmAdapter implements ChannelAdapter {

    private static final int MAX_DATA_BYTES = 4000;   // FCM's limit is 4 KB; keep headroom

    private final FirebaseMessaging fcm;
    private final DeviceRepository devices;

    @Override public Channel channel() { return Channel.PUSH; }

    @Override
    public DispatchResult send(Decision.Allow allowed, RenderedMessage msg) throws ChannelException {
        var tokens = allowed.addresses().pushTokens();          // active, non-stale, non-iOS-web
        if (tokens.isEmpty()) throw new PermanentChannelException("no_live_tokens", 0);

        var data = msg.asData();                                // title, body, url, image, tag, kind, sid
        if (bytes(data) > MAX_DATA_BYTES) throw new PermanentChannelException("payload_too_large", 0);

        var message = MulticastMessage.builder()
            .putAllData(data)                                   // data-only: the SW renders
            .setWebpushConfig(WebpushConfig.builder()
                .putHeader("TTL", String.valueOf(msg.ttl().toSeconds()))
                .putHeader("Urgency", msg.urgency())            // "high" for restock, else "normal"
                .build())
            .setAndroidConfig(AndroidConfig.builder()           // for a future native app
                .setTtl(msg.ttl().toMillis())
                .setPriority(msg.highPriority() ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                .build())
            .addAllTokens(tokens.stream().map(DeviceToken::value).toList())
            .build();

        BatchResponse batch;
        try {
            batch = fcm.sendEachForMulticast(message);
        } catch (FirebaseMessagingException e) {
            throw new TransientChannelException(e.getMessage(), e);
        }

        int ok = 0;
        for (int i = 0; i < batch.getResponses().size(); i++) {
            var r = batch.getResponses().get(i);
            if (r.isSuccessful()) { ok++; continue; }
            var token = tokens.get(i);
            switch (r.getException().getMessagingErrorCode()) {
                case UNREGISTERED -> devices.deactivate(token.id(), "unregistered");
                case SENDER_ID_MISMATCH -> devices.deactivate(token.id(), "sender_mismatch");
                case INVALID_ARGUMENT -> {
                    // Ambiguous: bad token OR bad payload. Prune only if FCM
                    // names the token; otherwise the bug is ours and pruning
                    // would silently destroy good subscribers.
                    if (mentionsToken(r.getException())) devices.deactivate(token.id(), "invalid_token");
                    else alerts.payloadRejected(allowed.template().key(), r.getException());
                }
                default -> { /* QUOTA_EXCEEDED, UNAVAILABLE, INTERNAL: transient */ }
            }
        }
        if (ok == 0) throw new TransientChannelException("all_tokens_failed", null);
        return DispatchResult.of(null, ok);   // FCM gives no single provider id for a multicast
    }
}
```

**One send, many devices.** A shopper with Chrome on their phone and on their laptop has two tokens. Both receive the message. The shared `tag` means that whichever one they act on, the other collapses rather than nagging twice. The `sends` row is per identity per intent, not per token, so frequency caps count **messages to a person**, not notifications to devices.

**Quota.** FCM enforces a per-project send rate. Check the current quota in the Firebase console before a festive campaign. The campaign executor's token bucket (Phase 6) keeps a single blast from exhausting it and starving back-in-stock alerts.

---

## 3. Push payload contract

Every push is data-only. The service worker (Phase 2 §5) renders it.

| Key | Required | Rule |
|---|---|---|
| `sid` | yes | `sends.id`; joins impression and click beacons back |
| `kind` | yes | intent key; also the default `tag` |
| `title` | yes | ≤ 40 characters. Android truncates hard. |
| `body` | yes | ≤ 90 characters. This is what shows collapsed. |
| `url` | yes | Absolute, same origin, with UTM parameters (§5) |
| `image` | no | 2:1, ≥ 720×360, product on a plain background. Chrome only; Firefox ignores it. |
| `icon` | no | Defaults to the brand icon |
| `tag` | no | Override to collapse related pushes, e.g. `restock:<variantId>` |

The content rules come from the India playbook: lead with the product and the size, not the brand. "Your size M is back — Linen Kurta" outperforms "New from BRAND". Hinglish variants are selected by delivery state for known customers and by `Accept-Language` for anonymous ones.

---

## 4. First four push intents

Each is a single-step cascade in this phase. Phases 4 and 5 add WhatsApp and email steps behind them without changing their triggers.

### `back_in_stock`
**Trigger:** `variant_restocked` (0 → positive) × `stock_waitlist` rows for that variant.
**TTL:** 1 hour, urgency `high`. A restock alert that arrives the next morning, after the size has sold out again, is worse than no alert.
**Stale tokens allowed:** yes. The shopper explicitly asked for this.
**Copy:** "Size M is back: Linen Kurta" / "Only a few pieces in this restock."

Fan-out is capped at the restocked quantity × 20. Notifying 2,000 people about 6 units sells out in minutes and leaves 1,994 people disappointed with you.

### `price_drop`
**Trigger:** `products/update` where a variant's price falls, × identities with that variant in an open cart or on a waitlist.
**TTL:** 24 hours.
**Copy:** "Price dropped on your bag item: ₹1,899 → ₹1,499". Real numbers, no "sale" framing.

### `browse_abandon`
**Trigger:** 3+ product views in one session, no add-to-cart, session ended (30 minutes idle).
**Delay:** 4 hours. **TTL:** 24 hours.
**Channel:** push only, permanently. Intent is too weak to pay for WhatsApp (`INDIA-PLAYBOOK.md` §3).

### `cart_recovery` (step 1 only)
**Trigger:** `cart_updated` with items. Restarts on every cart change.
**Delay:** 45 minutes. **TTL:** 12 hours.
**Exit:** `order_placed` for that cart.
**Copy:** scarcity, not discount. "Your M is still in your bag — 2 left."

---

## 5. Attribution

Every push URL carries:

```
utm_source=engage&utm_medium=push&utm_campaign=<intent_key>&utm_content=<sid>
```

The click beacon marks `sends.clicked_at` directly. Conversion attribution is a separate question, and it is answered with holdouts (Phase 7), not with UTMs. UTMs show where a click came from. They do not show whether the order would have happened anyway.

---

## 6. Structural enforcement

The one-door rule is enforced by the build, not by code review:

```java
@AnalyzeClasses(packages = "in.brand.engage")
class ArchitectureTest {

    @ArchTest
    static final ArchRule only_the_router_reaches_channels =
        noClasses().that().resideOutsideOfPackages("..orchestrator..", "..channels..")
            .should().dependOnClassesThat().resideInAPackage("..channels..")
            .because("every send must pass through policy (CLAUDE.md invariant 1)");

    @ArchTest
    static final ArchRule channels_do_not_know_policy =
        noClasses().that().resideInAPackage("..channels..")
            .should().dependOnClassesThat().resideInAnyPackage("..policy..", "..orchestrator..");
}
```

**Template lint** runs in CI and at startup. A utility or authentication template containing promotional language fails the build (`05-campaigns.md` §Templates). Push templates are linted too. They have no Meta category, but they have the same copy rules.

---

## Acceptance criteria

- [ ] ArchUnit tests pass. Adding a `channels` import to a controller fails the build.
- [ ] Policy suite from `04-backend-micronaut.md` passes, plus: WhatsApp utility without any WhatsApp grant → `NO_WHATSAPP_OPT_IN`; iOS-web-only identity → `UNREACHABLE` on push
- [ ] A restock of a waitlisted variant delivers a push to a real Android Chrome device in under 60 seconds
- [ ] `UNREGISTERED` from FCM deactivates that token in the same send cycle
- [ ] An `INVALID_ARGUMENT` caused by an oversized payload raises an alert and deactivates nothing
- [ ] A push click updates `sends.clicked_at` within 5 seconds
- [ ] A second cart change resets `cart_recovery` rather than queueing a second push
- [ ] Push frequency cap (3/day default) is counted per person across all intents

## Exit gate

Push intents live in production for 7 days. Click-through measured per intent. Zero sends bypassing the router, verified by querying for sends without a `config_snapshot_id`.
