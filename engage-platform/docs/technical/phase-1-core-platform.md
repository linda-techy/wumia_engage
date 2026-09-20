# Phase 1 — Core platform

**Weeks 1–2 · Owner: backend · Depends on: Phase 0 repo + Shopify app**

## Goal

Stand up the data spine: the identity graph, the append-only consent ledger, event ingestion, and verified intake of Shopify webhooks. Nothing is sent to customers in this phase. Everything later phases do is a function of what this phase records.

## Scope

**In:** Postgres schema and migrations, identity resolution, consent ledger, event store, Shopify **and Razorpay** webhook intake, App Proxy signature verification, health and metrics.
**Out:** any outbound message, the policy engine, the admin console.

> **Status: implemented.** The code in `core-domain/`, `ingest-api/` and `db/migration/V1–V6` is authoritative wherever it differs from the design notes below. Building it changed four things:
>
> | Design note below | What was built, and why |
> |---|---|
> | `webhook_receipts` (dedupe only) | **`webhook_inbox`**: the raw payload is stored in the same statement that dedupes. The controller acknowledges only after storing, so a crash after the 200 loses nothing, and a failed item retries with backoff. |
> | Merge only on a verified key | **Four trust levels** (STRONG, SESSION, BUYER, WEAK) in `resolve_identity()` (V6). This blocks gift-recipient merges without splitting a shopper whose phone was first seen on a failed Razorpay payment. The shipping-address phone never merges. |
> | Handlers parse JSON in Java | Payloads are extracted in **SQL files** (`ingest-api/src/main/resources/sql/`), so the exact queries that ship were tested against Postgres with fixture payloads. |
> | Consent rows stamped at write time | Stamped with **when the shopper chose**, so a late or retried webhook can never re-subscribe someone who unsubscribed, or re-grant WhatsApp after a STOP. |
>
> Verification: 17 database invariant tests, 23 identity tests (including 8 concurrent resolvers producing one identity), 24 SQL extraction checks, 40 unit tests, and end-to-end runs in natural, fully reversed and late delivery orders, all against Postgres 16. See `LOCAL-SETUP.md` §7 to run the tests yourself.

---

## 1. Schema

Base schema: `db/V1__core.sql` (identities, identity_keys, profiles, events, consents, suppressions, sends, templates, holdouts, conversions, spend_ledger) from the earlier spec. This phase adds the Shopify-specific and push-specific tables:

```sql
-- V3__shopify_and_push.sql

-- Every device that can receive push. Web tokens rot; the columns below
-- exist so we can tell a live subscriber from a dead one without sending.
CREATE TABLE devices (
  id                BIGSERIAL PRIMARY KEY,
  identity_id       UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  fcm_token         TEXT NOT NULL UNIQUE,
  platform          TEXT NOT NULL CHECK (platform IN ('WEB','ANDROID','IOS_APP','IOS_WEB')),
  browser           TEXT,                       -- chrome | edge | firefox | samsung | safari
  origin            TEXT NOT NULL,              -- https://brand.in
  sw_version        TEXT,                       -- service-worker build that minted the token
  permission_source TEXT NOT NULL,              -- add_to_cart | notify_me | thank_you | settings
  consent_copy_ver  TEXT NOT NULL,              -- exact soft-ask copy the user accepted
  active            BOOLEAN NOT NULL DEFAULT true,
  deactivated_reason TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now(),   -- client re-confirmed token
  last_sent_at      TIMESTAMPTZ,
  last_clicked_at   TIMESTAMPTZ
);
CREATE INDEX ON devices (identity_id) WHERE active;
CREATE INDEX ON devices (last_refreshed_at) WHERE active;

-- The soft-ask funnel. Without this you cannot tell whether a low
-- subscriber count is a traffic problem or a prompt problem.
CREATE TABLE push_prompt_events (
  id          BIGSERIAL PRIMARY KEY,
  anon_id     TEXT NOT NULL,                    -- first-party id from push.js
  identity_id UUID REFERENCES identities(id) ON DELETE SET NULL,
  surface     TEXT NOT NULL,                    -- add_to_cart | notify_me | thank_you
  step        TEXT NOT NULL CHECK (step IN
                ('soft_shown','soft_accepted','soft_dismissed',
                 'native_granted','native_denied','native_dismissed',
                 'token_minted','token_failed','ios_redirected_to_whatsapp')),
  browser     TEXT,
  platform    TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON push_prompt_events (surface, step, created_at);

-- Carts as Shopify reports them. Token normalised (see §4).
CREATE TABLE carts (
  cart_token      TEXT PRIMARY KEY,
  identity_id     UUID REFERENCES identities(id) ON DELETE SET NULL,
  item_count      INT  NOT NULL DEFAULT 0,
  total_paise     BIGINT,
  currency        TEXT NOT NULL DEFAULT 'INR',
  lines           JSONB NOT NULL DEFAULT '[]',  -- [{variant_id, title, size, qty, price_paise, image}]
  checkout_token  TEXT,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  converted_at    TIMESTAMPTZ,
  order_id        TEXT
);
CREATE INDEX ON carts (checkout_token) WHERE checkout_token IS NOT NULL;

-- Size-variant waitlist. Waitlisting at product level notifies people whose
-- size is still out of stock, which teaches them to ignore you.
CREATE TABLE stock_waitlist (
  identity_id   UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  variant_id    TEXT NOT NULL,
  product_handle TEXT NOT NULL,
  size_label    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  notified_at   TIMESTAMPTZ,
  PRIMARY KEY (identity_id, variant_id)
);
CREATE INDEX ON stock_waitlist (variant_id) WHERE notified_at IS NULL;

-- Last known availability, so restocks fire only on a 0 -> positive transition.
CREATE TABLE inventory_state (
  inventory_item_id TEXT PRIMARY KEY,
  variant_id        TEXT NOT NULL,
  available         INT  NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Superseded in the build by webhook_inbox (V1): see the status note above.
```

Processed `webhook_inbox` rows are pruned after 7 days. Shopify's retry window is shorter than that.

---

## 2. Identity resolution

The phone number is the primary key in India, not the email address. Normalise to E.164 without the plus sign, which is also WhatsApp's `wa_id` format:

```java
public final class Msisdn {
    private static final Pattern DIGITS = Pattern.compile("\\D");

    /** Returns 91XXXXXXXXXX for Indian mobiles, or empty if not plausibly a mobile. */
    public static Optional<String> normalise(String raw) {
        if (raw == null) return Optional.empty();
        var d = DIGITS.matcher(raw).replaceAll("");
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) d = "91" + d;
        // Indian mobiles start 6–9 after the country code. Landlines and
        // short codes cannot receive WhatsApp or DLT SMS, so reject early.
        if (d.length() == 12 && d.startsWith("91") && "6789".indexOf(d.charAt(2)) >= 0) {
            return Optional.of(d);
        }
        return Optional.empty();
    }
}
```

**Merge policy (as built, V6 `resolve_identity`).** Identities merge only when a call carries a **verified** strong key: a Shopify customer id from a signed source, a `wa_id` from a WhatsApp inbound, or a verified phone. Which other identities join the merge depends on the trust level of the key that reached them:

| Trust | Keys | Joins a merge? |
|---|---|---|
| STRONG | verified `shopify_customer`, `wa_id`, `phone` | Yes |
| SESSION | `anon`, `fcm_token`, `cart_token`, `checkout_token` | Yes: same browser session as the verified key |
| BUYER | the buyer's own contact phone/email | Only a **soft** identity (no verified customer/WhatsApp key), never a verified customer |
| WEAK | the **shipping-address** phone, anything else unverified | Never |

The shipping phone is WEAK because in India it is often a gift recipient's number. A customer ordering for their mother must not absorb the mother's identity, or one person's messages go to the other's WhatsApp. The buyer's contact phone is BUYER, so a shopper first seen on a failed Razorpay payment and then logging in stays one person. Concurrent resolutions of the same keys are serialised with advisory locks, so two pods can never mint two identities for one shopper.

Identity keys used in this build:

| Kind | Source | Verified? |
|---|---|---|
| `shopify_customer` | App Proxy `logged_in_customer_id`, order webhook | Yes |
| `phone` | Order webhook, checkout webhook | Only after a WhatsApp inbound or delivered utility message |
| `email` | Order webhook | No |
| `anon` | `push.js` first-party id | No |
| `fcm_token` | Token registration | No |
| `cart_token` | Cart sync, cart webhook | No |
| `checkout_token` | Checkout webhook, web pixel | No |

---

## 3. Consent ledger

Append-only, with withdrawals propagating across all marketing channels. The full design is in the earlier spec. One addition for this phase:

**Push permission is not marketing consent by itself.** A browser "Allow" grants the *capability* to push. The soft-ask the user accepted before it is what records the *consent*, and its exact copy is stored as evidence:

```java
consents.grant(identityId, Channel.PUSH, Purpose.MARKETING, "soft_ask:" + surface,
    Map.of("copy_version", copyVersion,
           "copy_text", copyText,          // exact words shown
           "page", pageUrl,
           "ua", userAgent,
           "pre_ticked", false));
```

If the soft-ask said "Get notified when your size is back", the consent covers restock alerts. Whether it covers general promotions is a legal judgement. Record the copy verbatim so that question can be answered later.

---

## 4. Shopify webhook intake

```java
@Controller("/shopify/webhooks")
@Secured(SecurityRule.IS_ANONYMOUS)          // authenticated by HMAC, not by session
public class ShopifyWebhookController {

    @Post(consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> receive(@Body byte[] raw,                         // raw bytes: HMAC is over these
                                   @Header("X-Shopify-Hmac-Sha256") String hmac,
                                   @Header("X-Shopify-Topic") String topic,
                                   @Header("X-Shopify-Webhook-Id") String deliveryId,
                                   @Header("X-Shopify-Shop-Domain") String shop) {

        if (!shop.equals(config.shopDomain()) || !hmacVerifier.verifyBase64(raw, hmac)) {
            return HttpResponse.unauthorized();
        }
        if (!receipts.firstSight("shopify", deliveryId, topic)) {
            return HttpResponse.ok();                     // retry of something we have
        }
        // Acknowledge inside Shopify's 5s budget. Processing happens off the
        // request thread; a slow journey must never cause a webhook retry.
        intake.submit(new ShopifyEnvelope(topic, deliveryId, raw));
        return HttpResponse.ok();
    }
}
```

Bind the body as `byte[]`. If Micronaut deserialises it to a DTO first, the bytes the HMAC was computed over are gone and every signature check fails.

### Topics and what each one records

| Topic | Records | Used by |
|---|---|---|
| `carts/create`, `carts/update` | `carts` row, `cart_updated` event | cart_recovery (Phase 5) |
| `checkouts/create`, `checkouts/update` | phone and email on identity, `checkout_updated` event | checkout_abandon, capability |
| `orders/create` | `order_placed`, conversion row, cart marked converted | every journey's exit |
| `orders/paid` | `order_paid` | order_tracking |
| `orders/fulfilled`, `fulfillments/create`, `fulfillments/update` | `order_shipped` and delivery states | order_tracking |
| `orders/cancelled`, `refunds/create` | cancellation and refund events | order_tracking |
| `inventory_levels/update` | availability, restock transition | back_in_stock |
| `customers/create`, `customers/update` | identity keys, Shopify's own marketing consent flags | consent sync |
| `app/uninstalled` | halts all sends for the shop | safety |

**Cart token normalisation.** The token returned by the storefront's `/cart.js` can carry a query-style suffix (`…?key=…`) that the `carts/update` webhook does not. Normalise by stripping everything from the first `?`. Then assert in a Phase 1 test, against a real development store, that a cart created in the browser and the matching webhook resolve to the same `cart_token`. This join is what connects a push token to a cart, so it has to be proven with real traffic.

**Inventory.** `inventory_levels/update` is per location. Re-read the variant's total `inventoryQuantity` through the Admin GraphQL API before comparing it against `inventory_state`. Fire `variant_restocked` only on a transition from 0 to a positive number. Without the stored previous state, every stock adjustment re-notifies the waitlist.

---

## 5. App Proxy verification

Every storefront request (token registration, cart sync, prompt events, the service worker itself) arrives through Shopify's App Proxy at `https://brand.in/apps/push/*`. Shopify appends `shop`, `path_prefix`, `timestamp`, `logged_in_customer_id` and `signature` to the query string.

```java
@Singleton
public class AppProxyVerifier {

    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    public ProxyContext verify(HttpRequest<?> req) {
        var params = req.getParameters();
        var signature = params.get("signature");
        if (signature == null) throw new UnauthorizedException("unsigned");

        // Sorted key=value pairs, multi-values comma-joined, concatenated with
        // NO separator, HMAC-SHA256 with the app secret, hex encoded.
        var message = params.names().stream()
            .filter(n -> !n.equals("signature"))
            .sorted()
            .map(n -> n + "=" + String.join(",", params.getAll(n)))
            .collect(Collectors.joining());

        if (!MessageDigest.isEqual(
                hmacHex(secret, message).getBytes(UTF_8), signature.getBytes(UTF_8))) {
            throw new UnauthorizedException("bad signature");
        }

        // The signature does not expire on its own. Reject stale timestamps,
        // or a captured URL can be replayed indefinitely.
        var ts = Instant.ofEpochSecond(Long.parseLong(params.get("timestamp")));
        if (Duration.between(ts, clock.instant()).abs().compareTo(MAX_SKEW) > 0) {
            throw new UnauthorizedException("stale");
        }

        var customerId = params.get("logged_in_customer_id");       // "" when logged out
        return new ProxyContext(params.get("shop"),
                                customerId == null || customerId.isBlank()
                                    ? Optional.empty() : Optional.of(customerId));
    }
}
```

### What the signature does and does not prove

The signature proves the request **came through Shopify's proxy for your shop**. It does **not** prove the request body is honest. Any visitor can POST anything to `/apps/push/register`, and Shopify will sign the query string on the way through.

So there are exactly two trust levels:

- **Trusted:** the signed query parameters, above all `logged_in_customer_id`. This is the only source of customer identity from the storefront.
- **Untrusted:** everything in the body. Validate it, rate-limit it, and never let it assert who the customer is.

The earlier Node prototype posted `customerId: {{ customer.id }}` in the body and trusted it. That lets anyone attach their device to any customer's profile. Here, the customer id comes from the signed parameter or not at all.

Shopify also strips the `Cookie` and `Set-Cookie` headers on proxied requests. Session state cannot ride on cookies here. The first-party `anon` id travels in the body, and it is treated as untrusted like everything else in the body.

---

## Acceptance criteria

- [ ] Flyway runs V1–V3 cleanly on an empty Postgres 16 and on a copy of staging
- [ ] A Shopify webhook with a tampered body returns 401. A replay of the same delivery id is acknowledged and not re-processed
- [ ] The webhook handler acknowledges within 500 ms at p99 under 50 requests/s
- [ ] An App Proxy request with a timestamp older than 5 minutes returns 401
- [ ] A register call whose body names a different customer than `logged_in_customer_id` binds to the signed customer, and the mismatch is logged
- [ ] A browser-created cart and its `carts/update` webhook resolve to one `cart_token` on the development store
- [ ] A restock from 0→5 emits one `variant_restocked` event; a 5→8 adjustment emits none
- [ ] `orders/create` for a returning customer resolves to their existing identity by `shopify_customer`, not a new one

## Exit gate

Staging receives live webhooks from the development store for 48 hours with zero HMAC failures and zero unprocessed deliveries.
