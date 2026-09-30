# P2 — Shopify storefront + FCM web push · Implementation

**Weeks 2–4 · Owners: BE1 (server), FE (storefront + extensions) · Design:** [`technical/phase-2-shopify-web-push.md`](../technical/phase-2-shopify-web-push.md)

## Outcome

Shoppers on Android and desktop grant push through a soft-ask at a high-intent moment, and their tokens land in `devices` against a verified identity. iOS and in-app-browser shoppers see the WhatsApp opt-in instead. The cart checkbox and the Thank you page both record WhatsApp opt-ins with evidence. The pixel records checkout timing without any PII.

**Nothing is sent in this phase.** Sending starts in P3, after the policy engine exists. The only messages that reach a device in P2 are Firebase-console test messages.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P2-T01 | Proxy endpoints + device repository + rate limiter | BE1 | 2 d | P1-T01 |
| P2-T02 | Service worker bundle served through the proxy | FE + BE1 | 1 d | T01 |
| P2-T03 | Theme app extension (app embed) | FE | 1 d | P0-T05 |
| P2-T04 | `engage-push.js`: soft-ask, platform detection, cart opt-in | FE | 3 d | T02, T03 |
| P2-T05 | Thank you page extension + session-token verifier | FE + BE1 | 2 d | T01 |
| P2-T06 | Web pixel + pixel ingest endpoint | FE + BE1 | 1.5 d | T01 |
| P2-T07 | Consent copy registration (interim, before the P6 UI) | BE1 | 0.5 d | T01 |
| P2-T08 | Token lifecycle job | BE1 | 1 d | T01 |
| P2-T09 | V11 funnel views + browser test matrix | FE + BE2 | 1.5 d | T04 |

**Order:** T07 first (T01 rejects unknown copy versions), then T01 → T02 → T03 → T04. T05, T06 and T08 can run in parallel with T04.

---

### ☑ P2-T01 — Proxy endpoints

> **Done 2026-09-27.** Built as `StorefrontProxyController` + `SubscriberService` (device SQL since moved to `core-persistence` `DeviceRepository` in P3-T01), tests in `StorefrontSubscriberTest` (12, Postgres). The customer allowlist gates every write; prompt events are identity-less. A real `/apps/push/register` from the dev store created `devices` #1 (Chrome, `add_to_cart`, `push_v1`) with a `push/marketing` grant; a repeat register added no rows.

The App Proxy forwards `https://<store>/apps/push/*` to `https://<ingest>/shopify/proxy/*` with a signature. `AppProxyVerifier` (core-domain) already verifies it and returns `ProxyContext(shop, Optional<loggedInCustomerId>)`.

**Files**
```
ingest-api/src/main/java/in/brand/engage/ingest/storefront/StorefrontProxyController.java
ingest-api/src/main/java/in/brand/engage/ingest/storefront/ProxyRequestVerifier.java   # wraps AppProxyVerifier for HttpRequest
ingest-api/src/main/java/in/brand/engage/ingest/storefront/RateLimiter.java           # in-memory token bucket per key
ingest-api/src/main/java/in/brand/engage/ingest/storefront/dto/*.java                 # records with @Serdeable + validation
ingest-api/src/main/java/in/brand/engage/ingest/db/DeviceRepository.java
ingest-api/src/main/java/in/brand/engage/ingest/db/CopyVersions.java
ingest-api/src/main/resources/sql/device_upsert.sql
ingest-api/src/test/java/in/brand/engage/ingest/storefront/StorefrontProxyTest.java
```

**Endpoints** (all: verify → rate-limit → validate → act)

| Path | Body | Effect |
|---|---|---|
| `POST /register` | `anonId, token, browser, platform, surface, copyVersion, page, cartToken?` | resolve identity; upsert device; grant push/marketing with evidence; link cart |
| `POST /refresh` | `anonId, token, previous?` | bump `last_refreshed_at`; rotate if the token changed |
| `POST /unregister` | `anonId, token` | deactivate (`user_off`); withdraw push consent |
| `POST /cart` | `anonId, cartToken` | `CartTokens.normalise`, attach cart to identity |
| `POST /notify-me` | `anonId, variantId, productHandle, sizeLabel` | `stock_waitlist` row |
| `POST /prompt-event` | `anonId, surface, step, browser, platform` | `push_prompt_events` row |
| `POST /engagement` | `sid, kind: impression\|click` | `sends.clicked_at` (P3 uses it; accept and store now) |

**Identity rules (invariant 9)**
- The customer id comes **only** from `ProxyContext.loggedInCustomerId`. A `customerId` in the body is ignored; the DTOs do not have that field.
- With a signed customer id: `IdentityResolver` with `Key.shopifyCustomer(id)` (STRONG) plus `Key.anon(anonId)` (SESSION). The anonymous identity merges into the customer.
- Without: `Key.anon(anonId)` only. An FCM token is a SESSION key and may be added.
- `anonId` must match `^[A-Za-z0-9_-]{16,64}$`. Reject anything else with 400.

**Rate limits** (per anonId, and per client IP as `X-Forwarded-For`'s first hop from Shopify): register 10/h, refresh 20/h, prompt-event 120/h, engagement 600/h. In-memory is acceptable until P7; a limit that resets on pod restart is fine for abuse at this scale. Return 429 with `Retry-After`.

**Consent write:** `consents` row with `channel=push`, `purpose=marketing`, `source='soft_ask:<surface>'`, `copy_version`, and `occurred_at` = the client's click time **if** it is within 10 minutes of the server time, otherwise the server time (invariant 10). `evidence` holds the verbatim copy, page, user agent, `pre_ticked: false` and `ref = 'push:<anonId>@<occurred_at>'`; the insert is skipped when a row with that `evidence->>'ref'` exists, the same way `ConsentWriter.syncShopifyEmailConsent` does it.

**Tests**
- Valid signature → 200; tampered query → 401; timestamp 6 min old → 401; wrong shop → 401.
- Body with a forged `customerId` field → ignored; device bound to the signed customer.
- Anonymous register, then a logged-in register with the same anonId → one identity, with both keys.
- Unknown `copyVersion` → 422, nothing written.
- Same token registered twice → one device row.
- 11th register in an hour → 429.

**Done when:** `./gradlew :ingest-api:test --tests '*StorefrontProxyTest*'` is green and a real `/apps/push/register` from the dev store creates a `devices` row.

**Claude Code prompt**
> P2-T01. Build `StorefrontProxyController` and its supporting classes as listed. Verify every request with the existing `AppProxyVerifier`. Identity comes only from the signed `logged_in_customer_id` (CLAUDE.md invariant 9): DTOs must not have a customer-id field. Consent rows follow invariant 10. Parse and write with SQL files like the existing handlers. Write the listed tests first.

---

### ☑ P2-T02 — Service worker

> **Done 2026-09-27.** `/shopify/proxy/sw.js` serves `200`, `application/javascript`, `no-cache`, `X-Engage-SW`. The Firebase web config reaches the worker in its registration URL (single source: the theme editor). Firebase pinned to 12.19.0 on both sides. A data-only FCM test message to device #1 showed a notification with the tab closed; impression and click beacons reached `/engagement`.

Browsers only allow a service worker to control paths at or below its own URL. The App Proxy makes `/apps/push/sw.js` a same-origin URL, so the scope is `/apps/push/`. That scope is enough to receive push; the page registers it explicitly and passes the registration to `getToken()`.

**Files**
```
shopify-extension/sw/src/sw.js                     # source; Firebase messaging compat bundle
shopify-extension/sw/build.mjs                     # esbuild → dist/sw.js, injects version + firebase config
ingest-api/src/main/resources/static/sw.js         # build output, copied by Gradle
ingest-api/src/main/java/in/brand/engage/ingest/storefront/SwBundle.java
```

**Rules**
- `Cache-Control: no-cache` (not `no-store`), plus `X-Engage-SW: <version>`.
- The Firebase web config (`apiKey`, `projectId`, `messagingSenderId`, `appId`) is public and is injected at build time from `FIREBASE_WEB_*` env vars. The service-account JSON never goes near it.
- Data-only messages: the worker renders `title`, `body`, `icon`, `image`, `tag`, `url` from `data` (payload contract in phase-3 §3), fires an `impression` beacon on show and a `click` beacon on click, then `clients.openWindow(url)` only if `url` is same-origin.

**Done when:** `curl -I https://<store>/apps/push/sw.js` shows `200`, `application/javascript`, `no-cache` and the version header; a Firebase console test message shows a notification with the tab closed.

---

### ☑ P2-T03 — Theme app extension

> **Done 2026-09-27.** `push-embed` released to wumikaEngage-dev; enabled in the revamp theme with the Firebase web config and VAPID key.

`shopify app generate extension --template theme_app_extension --name push-embed`, then the files in phase-2 §3. The embed block outputs `window.EngageConfig` (VAPID key, Firebase web config, proxy base `/apps/push`, copy versions, soft-ask copy) and loads `engage-push.js` deferred.

**Done when:** the embed is toggled on in the theme editor of the dev store and `window.EngageConfig` is present on every page.

---

### ◐ P2-T04 — `engage-push.js`

> **Unit tests done 2026-09-30; the manual matrix (T09) remains.** `shopify-extension/tests/engage-push.test.js`, Vitest 3.2 + jsdom 26 (pinned: the latest versions need Node 22), `npm test`, and now a step in the CI test job, so a failing test blocks the deploy. 18 tests: the 8 user agents (Android Chrome, desktop Chrome, Firefox, macOS Safari and installed iOS get the soft ask; iOS Safari in a tab, Instagram and Facebook get WhatsApp and log `ios_redirected_to_whatsapp`), plus iPadOS reporting itself as a Mac and a browser without push; the native prompt only after "Yes" in the same click; "Not now" remembered 14 days; a blocked browser left alone; a successful `/cart/add.js` opens the ask; the WhatsApp cart attributes; notify-me's waitlist payload. Mutation check: dropping the in-app test fails the Instagram and Facebook cases; writing `'1'` instead of `'yes'` fails the cart case. Bundle 5.3 KB gzipped (limit 12 KB).
> - **The cart attribute is `_engage_wa_optin=yes`, not `=1`** as written below: `ConsentWriter` matches `'yes'`, and the test pins it so the two cannot drift.

**Surfaces**
| Surface | When the soft-ask shows | Copy leads with |
|---|---|---|
| `add_to_cart` | after the first add-to-cart, once per 14 days | "Get price-drop and stock alerts for your bag" |
| `notify_me` | on an out-of-stock size, the button *is* the ask | "Tell me when size M is back" |
| `thank_you` | not in the theme; handled by T05 for WhatsApp | — |

**Platform routing** (decide before rendering anything)
```js
const ua = navigator.userAgent;
const inApp = /FBAN|FBAV|Instagram/.test(ua);
const ios = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const pushCapable = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

if (inApp || (ios && !standalone) || !pushCapable) showWhatsAppOptIn();   // and log ios_redirected_to_whatsapp
else if (Notification.permission === 'denied') {/* show nothing */}
else showSoftAsk();
```

**Sequence** (phase-2 §2): soft-ask accepted → `Notification.requestPermission()` **in the same click handler** → register `/apps/push/sw.js` with scope `/apps/push/` → `getToken({vapidKey, serviceWorkerRegistration})` → `POST /apps/push/register`. Log every step to `/prompt-event`.

**Weekly refresh:** on page load, if `localStorage.engage_refreshed_at` is more than 7 days old (wrapped in try/catch), call `getToken()` again and `POST /refresh` with the previous token.

**Cart opt-in checkbox** (WhatsApp, non-Plus, phase-2 §6.1): on the cart page and drawer, an unticked checkbox. When ticked, `POST /cart/update.js` with attributes `_engage_wa_optin=1`, `_engage_wa_copy=<wa copy version>`, `_engage_wa_at=<ISO time>`. They flow to the order's `note_attributes`, which `ConsentWriter.grantWhatsAppFromOrderAttributes` already reads. Unticking writes empty strings.

**Rules**
- The native prompt never appears without a soft-ask acceptance on the same gesture.
- Never pre-tick the WhatsApp box. A pre-ticked box is not consent under DPDP, and Meta will not accept it as opt-in evidence.
- No framework. The bundle stays under 12 KB gzipped, excluding the Firebase SDK, which loads only after the soft-ask is accepted.

**Tests:** Vitest with jsdom for routing (8 UA cases: Android Chrome, desktop Chrome, Firefox, macOS Safari, iOS Safari, iOS standalone, Instagram, Facebook) and for the cart attribute payload.

**Done when:** the unit tests pass and the manual matrix in T09 is green for Android Chrome and iOS Safari.

---

### ◐ P2-T05 — Thank you page extension

> **Built 2026-09-30; the dev-store check needs the block placed, the checkout phone label edited, and network access allowed.** `SessionTokenVerifierTest` (7) with vectors signed independently by Python `hmac`: valid, guest (no `sub`), `exp`/`nbf` at ±10 s, wrong `aud`, wrong `dest`, `alg: none`, `alg: HS512`, wrong key, edited payload, malformed. `ThankYouOptInTest` (5): opt-in before `orders/create` kept and applied when the order lands; after it, applied at once to the order's buyer (a `phone` in the request is ignored); replay → one consent row; bad token, `alg: none`, unregistered copy or a bad order id records nothing; CORS only for `https://extensions.shopifycdn.com`.
>
> Where the build differs from the text below:
> - **Consent model (decided 2026-09-30, "as most Indian D2C stores do"):** WhatsApp **order updates** go to the checkout phone on a **notice** basis, and **marketing** needs an explicit tap. Not pre-ticked boxes: those are not valid consent under DPDP s.6 and drive blocks that lower the WhatsApp quality rating.
>   - `checkout_notice_v1` (V12, whatsapp, `{transactional}`, surface `checkout_notice`): the checkout phone field's label, set in Shopify admin → checkout language, must read exactly `Phone (for order and delivery updates on WhatsApp/SMS)`. From `CHECKOUT_NOTICE_SINCE` (blank = off; set it only after the label is live, since recording a notice that was never shown would be false evidence), each order with a phone grants WhatsApp transactional (`source='checkout_notice'`, evidence: order, phone source, text, `basis=dpdp_s7a_order_updates`, `pre_ticked=false`), **only if the person has no WhatsApp transactional record at all**: a STOP is never overridden by a later order. STOP handling is P4-T04. `CheckoutNoticeConsentTest` (4).
>   - `ty_wa_v1` (V12, whatsapp, `{transactional,marketing}`, surface `thank_you`): the block's text, verbatim: `Get order updates, new arrivals and offers from WUMIKA on WhatsApp. Reply STOP anytime to opt out.` It is the explicit marketing opt-in.
>   - Registered by migration V12 (like V7 for `push_v1`), not T07's psql seed. P2-T07 still covers `wa_v1` and `wa_inthread_v1`. Push is unchanged: the browser's own "Allow" is the consent and cannot be assumed.
> - **Paths:** `core-domain/.../core/shopify/SessionTokenVerifier` (with a small strict JSON reader: no library), `ingest-api/.../web/ThankYouController`, logic in `push/ThankYouOptIns`.
> - **Race:** the opt-in and the order webhook both take a per-order advisory lock (`ConsentWriter.lockOrder`), so an opt-in arriving mid-webhook is never left pending.
> - **The ingest URL is an extension setting** (`ingest_url`) filled in the checkout editor, so one extension serves the dev and live apps. Without it the block renders nothing. 200 = consent recorded, 202 = waiting for the order.

**Files**
```
shopify-extension/extensions/thankyou-whatsapp/            # from phase-2 §6.2
core-domain/src/main/java/in/brand/engage/shopify/SessionTokenVerifier.java
core-domain/src/test/java/in/brand/engage/shopify/SessionTokenVerifierTest.java
ingest-api/src/main/java/in/brand/engage/ingest/storefront/ThankYouController.java
```

**`SessionTokenVerifier`**: HS256 JWT, key = `SHOPIFY_API_SECRET`. Check the signature with a constant-time compare, `alg == HS256` exactly (reject `none` and anything else), `aud == SHOPIFY_CLIENT_ID`, `dest` host `== SHOPIFY_SHOP_DOMAIN`, `exp` and `nbf` with 10 s leeway. Pure Java: no JWT library is needed for one algorithm, and not having one removes the algorithm-confusion class of bugs.

**Controller:** `POST /shopify/thankyou/whatsapp-optin` (not behind the App Proxy; CORS allows the checkout origin `https://extensions.shopifycdn.com`). The order id arrives as a GID: keep the numeric tail. Write `pending_optins`, then call `ConsentWriter.applyPendingOptIns` if the order is already in `orders`. The phone always comes from the order, never from the request.

**Tests:** signature vectors computed independently (Python `hmac`), expired, wrong `aud`, wrong `dest`, `alg: none`; opt-in before `orders/create` → applied when the order lands; opt-in after → applied immediately; replay → one consent row.

**Done when:** the tests pass and a dev-store test order shows the block, and a click produces a WhatsApp consent row with copy version `ty_wa_v1`.

---

### ◐ P2-T06 — Web pixel

> **Built 2026-09-30; the dev-store check waits for the scope approval, `PIXEL_WRITE_KEY` on the server and `webPixelCreate`.** `PixelIntakeTest` (9): a product view stored with no identity and no identity created; email and phone in the body dropped; a replay stored once; checkout steps move `last_step` forward and a late earlier step cannot move it back; a step for an unknown checkout kept as an event only; wrong or missing key 401; unlisted names, bad timestamps and missing required fields 400; the 301st event from one browser in an hour 429; the CORS preflight from the sandbox's `null` origin allowed. `./gradlew build` 267 tests.
>
> Where the build differs from the text below:
> - **The controller is `web/PixelController`, the logic `pixel/PixelEvents`** (the existing packages); no SQL file, the one UPDATE is inline.
> - **`product_added_to_cart` is also accepted**: browse_abandon needs "no add-to-cart" from the same browser.
> - **The pixel reads the push embed's `engage:anon` from the storefront's localStorage** (`browser.localStorage`, top frame) and sends it as `anonId`. Ingest stores it in the event props only; a consumer may use it to find an identity the push embed *already* linked (identity key `anon`), never to create one.
> - `last_step` holds the pixel event name (`payment_info_submitted`, etc.), not the `contact | shipping | payment` in V3's column comment. It moves only to a later pixel timestamp. A step that arrives before the checkout webhook created the row is kept as an event only.
> - Blank `PIXEL_WRITE_KEY` → 503 rather than refusing to start, so a deploy without it stays healthy. CORS (`@CrossOrigin`) is opened on this route only.
> - Scopes `write_pixels,read_customer_events` added to `shopify.app.dev.toml`; the pixel is `extensions/engage-pixel` (strict sandbox; loads only with analytics + marketing consent where the region requires consent).

**Files**
```
shopify-extension/extensions/engage-pixel/                    # from phase-2 §7
ingest-api/src/main/java/in/brand/engage/ingest/storefront/PixelController.java
ingest-api/src/main/resources/sql/pixel_event.sql
```

**Endpoint:** `POST /pixel/events`. Auth by `X-Engage-Key` compared with `PIXEL_WRITE_KEY` in constant time. Rate limit 300/h per `clientId`. Accept only the listed event names plus `product_viewed` (used by `browse_abandon` in P3). Write to `events` with `source='pixel'`, `identity_id` NULL, dedupe key `pixel:<clientId>:<name>:<at>`. Update `checkouts.last_step` for the checkout token if the checkout exists. **Never** create or merge identities from the pixel.

**Config:** `PIXEL_WRITE_KEY` in `local.env.example` and `LOCAL-SETUP.md` §1.

**Done when:** a dev-store checkout that stops at payment shows `last_step = 'payment_info_submitted'`, and the request log shows no email or phone in pixel bodies.

---

### ☐ P2-T07 — Consent copy registration (interim)

P6 builds the registry screen. Until then, copy versions are registered by a seed SQL file run with `psql`, so the storefront can go live:

```
db/seed/consent_copy_versions.sql   # push_v1, wa_v1 (cart), ty_wa_v1, wa_inthread_v1
```

Each row holds the **verbatim** text shown to the shopper and the purposes it covers. `ty_wa_v1` covers `{transactional}` only (the `purpose` enum is `transactional | marketing`); its text says "dispatch, delivery and exchange updates". A later marketing opt-in needs its own copy version that names offers.

**Done when:** the four versions exist and `CopyVersions.requireKnown` accepts them.

---

### ◐ P2-T08 — Token lifecycle job

> **Built 2026-09-30; the gauge waits for P1-T05 (no metrics library yet).** `worker/TokenLifecycle`: 02:30 IST (`@Scheduled` cron, zone Asia/Kolkata), one pass per night across workers via `pg_try_advisory_xact_lock` (the others skip). `TokenLifecycleTest` (3): fresh → `active`, 45 days → `stale` and still active, 200 days → deactivated `dormant`; a click within 180 days keeps an unrefreshed device, one 190 days ago does not; a second pass changes nothing. `./gradlew build` 277 tests.
> - **V11 (`push_prompt_funnel`, `device_health`) is built now** with this task, because the stale state is that view; P2-T09 adds the browser matrix on top. `device_health` also carries `deactivated_reason`.

Nightly, 02:30 IST, one runner via advisory lock:
- `active AND last_refreshed_at < now() - 180 days AND (last_clicked_at IS NULL OR last_clicked_at < now() - 180 days)` → deactivate, reason `dormant`.
- Update gauge `engage_push_tokens{state,browser}` from the V11 view.

Stale (30 days) is a **view**, not a state change: stale tokens stay active for back-in-stock.

**Done when:** a test with three devices (fresh, 45 days, 200 days) produces active/stale/deactivated respectively.

---

### ☐ P2-T09 — Funnel views + browser test matrix

**Migration `V11__push_funnel_views.sql`**
```sql
CREATE VIEW push_prompt_funnel AS
SELECT date_trunc('day', created_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist,
       surface, platform,
       count(*) FILTER (WHERE step = 'soft_shown')      AS soft_shown,
       count(*) FILTER (WHERE step = 'soft_accepted')   AS soft_accepted,
       count(*) FILTER (WHERE step = 'native_granted')  AS native_granted,
       count(*) FILTER (WHERE step = 'token_minted')    AS token_minted,
       count(*) FILTER (WHERE step = 'ios_redirected_to_whatsapp') AS ios_redirected
  FROM push_prompt_events
 GROUP BY 1, 2, 3;

CREATE VIEW device_health AS
SELECT id, identity_id, platform, browser, active,
       CASE WHEN NOT active THEN 'inactive'
            WHEN last_refreshed_at < now() - interval '30 days' THEN 'stale'
            ELSE 'active' END AS state
  FROM devices;
```

`devices.last_refreshed_at` and `last_clicked_at` already exist (V3).

**Manual matrix:** phase-2 §10, recorded in `docs/qa/p2-matrix.md` with the device, browser version, date and result for each row.

**Done when:** every matrix row is recorded as pass, and `SELECT * FROM push_prompt_funnel` shows real dev-store traffic.

---

## Exit gate

- [ ] All phase-2 acceptance criteria ticked
- [ ] Live on production for 7 days with registration errors under 1%
- [ ] Funnel measured per surface from `push_prompt_funnel`
- [ ] No push sent to a real customer yet
