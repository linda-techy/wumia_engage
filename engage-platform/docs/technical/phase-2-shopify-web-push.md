# Phase 2 — Shopify storefront: FCM web push and opt-in capture

**Weeks 2–4 · Owner: frontend + backend · Depends on: Phase 1**

## Goal

Collect push subscribers and WhatsApp opt-ins from the Shopify storefront, bind them to the right customer securely, and keep the subscriber base alive. Nothing promotional is sent yet. Phase 3 does the sending. This phase ends with a growing, clean, correctly attributed subscriber base.

Push is the free volume channel (see `INDIA-PLAYBOOK.md` §2). The sooner it goes live, the sooner the tokens start accumulating.

## Scope

**In:** theme app extension (app embed), service worker served through the App Proxy, FCM token minting and lifecycle, soft-ask on three surfaces, iOS routing, cart sync, WhatsApp opt-in via a cart attribute and on the Thank you page, web pixel for checkout timing, prompt-funnel metrics.
**Out:** sending notifications (Phase 3), native app push (Phase 7 appendix).

---

## 1. The Shopify constraint that shapes everything

A service worker controls only URLs at or below its own path, and it must be served from the page's origin. Shopify does not let you put a file at `https://brand.in/sw.js`. Theme assets are served from `cdn.shopify.com`, which is a different origin, and browsers refuse to register a cross-origin service worker.

The **App Proxy** fixes this. Shopify forwards `https://brand.in/apps/push/*` to your server, so the worker is served same-origin at `/apps/push/sw.js` with scope `/apps/push/`.

That narrow scope is fine. Push events are delivered to a registration regardless of which pages it controls. The worker never needs to intercept page fetches; it only needs to receive pushes and handle clicks. Because the default scope for `/apps/push/sw.js` is `/apps/push/`, no `Service-Worker-Allowed` header is needed, which matters because Shopify's proxy does not reliably pass it through.

The one consequence to remember: Firebase looks for `/firebase-messaging-sw.js` at the root by default. You must pass the registration explicitly to `getToken()`. Forgetting this is the single most common reason FCM "works locally and fails on Shopify".

---

## 2. Registration sequence

```
Browser (push.js)             Shopify proxy            ingest-api                  FCM
      │                             │                       │                        │
      │ user taps "Notify me"       │                       │                        │
      │── soft-ask shown ──────────────────────── prompt-event(soft_shown) ─────────▶│
      │── user accepts ─────────────────────────── prompt-event(soft_accepted) ─────▶│
      │── Notification.requestPermission()  (on the same user gesture)               │
      │◀─ "granted"                 │                       │                        │
      │── register /apps/push/sw.js ▶ GET sw.js ──(signed)──▶ serve worker           │
      │── getToken({vapidKey, serviceWorkerRegistration}) ──────────────────────────▶│
      │◀──────────────────────────────────────────────────────────────── token ──────│
      │── POST /apps/push/register ▶ adds signature, ────────▶ verify signature       │
      │   {token, anonId, surface,     logged_in_customer_id   bind identity:         │
      │    copyVersion, cartToken}                              customer id (signed)  │
      │                             │                          else anon id          │
      │                             │                          upsert device          │
      │                             │                          grant push consent     │
      │◀──────────────────── 200 {deviceId} ───────────────────│                        │
```

---

## 3. Theme app extension

Deliver the storefront code as an **app embed block** in a theme app extension, not as edits to `theme.liquid`. It survives theme updates and theme switches, the merchant toggles it in the theme editor, and it uninstalls cleanly.

```
shopify-extension/extensions/push-embed/
├─ shopify.extension.toml
├─ blocks/engage-embed.liquid
├─ assets/engage-push.js
├─ assets/engage-push.css
└─ locales/en.default.json
```

```liquid
{%- comment -%} blocks/engage-embed.liquid {%- endcomment -%}
<script>
  window.EngageConfig = {
    proxy: "/apps/push",
    vapidKey: {{ block.settings.vapid_key | json }},
    firebase: {
      apiKey: {{ block.settings.fb_api_key | json }},
      projectId: {{ block.settings.fb_project_id | json }},
      messagingSenderId: {{ block.settings.fb_sender_id | json }},
      appId: {{ block.settings.fb_app_id | json }}
    },
    // Display-only. Identity binding uses the proxy's signed
    // logged_in_customer_id, never this value.
    loggedIn: {{ customer | json | default: 'null' }} != null,
    copy: {
      push: {{ block.settings.push_copy | json }},
      pushVersion: {{ block.settings.push_copy_version | json }},
      whatsapp: {{ block.settings.wa_copy | json }},
      whatsappVersion: {{ block.settings.wa_copy_version | json }}
    }
  };
</script>
<script type="module" src="{{ 'engage-push.js' | asset_url }}" defer></script>
<link rel="stylesheet" href="{{ 'engage-push.css' | asset_url }}">

{% schema %}
{
  "name": "Engage push",
  "target": "body",
  "settings": [
    { "type": "text", "id": "vapid_key", "label": "FCM Web Push certificate (public key)" },
    { "type": "text", "id": "fb_api_key", "label": "Firebase API key" },
    { "type": "text", "id": "fb_project_id", "label": "Firebase project id" },
    { "type": "text", "id": "fb_sender_id", "label": "Firebase sender id" },
    { "type": "text", "id": "fb_app_id", "label": "Firebase app id" },
    { "type": "text", "id": "push_copy", "label": "Push soft-ask text",
      "default": "Get an alert when your size is back or your bag price drops." },
    { "type": "text", "id": "push_copy_version", "label": "Push copy version", "default": "push_v1" },
    { "type": "text", "id": "wa_copy", "label": "WhatsApp opt-in text",
      "default": "Send me order and delivery updates from BRAND on WhatsApp" },
    { "type": "text", "id": "wa_copy_version", "label": "WhatsApp copy version", "default": "wa_v1" }
  ]
}
{% endschema %}
```

Everything in `EngageConfig` is public by design. The Firebase web config is an identifier, not a credential. Restrict the API key by HTTP referrer in Google Cloud (Phase 0 §4). The VAPID value here is the **public** key. The private key never leaves Firebase.

**Copy versions are consent evidence.** When copy changes, bump the version. The server stores the version and the exact text with every grant (Phase 1 §3). Letting the text be edited in the theme editor without a version bump breaks the audit trail, so the server rejects a registration whose `copyVersion` it has not seen registered in admin (Phase 6).

---

## 4. Client: `engage-push.js`

```js
// assets/engage-push.js — ES module. Firebase modular SDK from gstatic.
// Pin FIREBASE_VERSION to the exact current release and bump deliberately.
const FIREBASE_VERSION = '12.x.x';
const CFG = window.EngageConfig;
const WEEK = 7 * 24 * 3600 * 1000;

/* ------------------------------ environment ------------------------------ */

const ua = navigator.userAgent;
const isIOS = /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
const isStandalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

// iOS Safari only supports web push for sites installed to the Home Screen.
// Almost no storefront visitor does that, so on iOS we never show a push ask:
// it would be a prompt that cannot work. WhatsApp is the iOS channel.
const pushCapable = 'serviceWorker' in navigator && 'PushManager' in window
                 && 'Notification' in window && (!isIOS || isStandalone);

const store = {
  get: (k) => { try { return localStorage.getItem('engage:' + k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem('engage:' + k, v); } catch {} }
};

// First-party anonymous id. Untrusted server-side; used only to stitch
// events from one browser before a verified identity exists.
const anonId = store.get('anon') || (() => {
  const id = crypto.randomUUID(); store.set('anon', id); return id;
})();

const post = (path, body) => fetch(`${CFG.proxy}${path}`, {
  method: 'POST', keepalive: true,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ anonId, ...body })
});

const track = (surface, step) =>
  post('/prompt-event', { surface, step, platform: isIOS ? 'IOS_WEB' : 'WEB' }).catch(() => {});

/* ------------------------------- firebase -------------------------------- */

let messagingP;
function messaging() {
  return messagingP ??= (async () => {
    const [{ initializeApp }, m] = await Promise.all([
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-app.js`),
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-messaging.js`)
    ]);
    if (!(await m.isSupported())) throw new Error('fcm-unsupported');
    return { m, instance: m.getMessaging(initializeApp(CFG.firebase)) };
  })();
}

async function registration() {
  const reg = await navigator.serviceWorker.register(`${CFG.proxy}/sw.js`, {
    scope: `${CFG.proxy}/`, updateViaCache: 'none'
  });
  await navigator.serviceWorker.ready;   // getToken rejects on an installing worker
  return reg;
}

async function mintToken() {
  const { m, instance } = await messaging();
  return m.getToken(instance, {
    vapidKey: CFG.vapidKey,
    serviceWorkerRegistration: await registration()   // required: SW is not at root
  });
}

/* ------------------------------- soft ask -------------------------------- */

/**
 * Show our own prompt first; only call the browser prompt after the shopper
 * says yes. A browser "Block" is permanent for the origin, and Chrome quietens
 * prompts for sites with poor accept rates, so the native prompt is spent only
 * on people who already agreed.
 */
export async function ask(surface) {
  if (!pushCapable) {
    if (isIOS) { track(surface, 'ios_redirected_to_whatsapp'); showWhatsAppOptIn(surface); }
    return null;
  }
  if (Notification.permission === 'denied') return null;
  if (Notification.permission === 'granted') return subscribe(surface);

  const dismissedAt = Number(store.get('dismissed') || 0);
  if (Date.now() - dismissedAt < 2 * WEEK) return null;       // do not nag

  track(surface, 'soft_shown');
  const accepted = await softAskDialog(CFG.copy.push);
  if (!accepted) { store.set('dismissed', String(Date.now())); track(surface, 'soft_dismissed'); return null; }
  track(surface, 'soft_accepted');

  const result = await Notification.requestPermission();      // still within the click gesture
  track(surface, result === 'granted' ? 'native_granted'
               : result === 'denied' ? 'native_denied' : 'native_dismissed');
  return result === 'granted' ? subscribe(surface) : null;
}

async function subscribe(surface) {
  try {
    const token = await mintToken();
    const res = await post('/register', {
      token, surface,
      copyVersion: CFG.copy.pushVersion,
      copyText: CFG.copy.push,
      page: location.pathname,
      cartToken: await cartToken()
    });
    if (!res.ok) throw new Error(`register ${res.status}`);
    store.set('token', token);
    store.set('refreshed', String(Date.now()));
    track(surface, 'token_minted');
    return token;
  } catch (e) {
    track(surface, 'token_failed');
    return null;
  }
}

/* ---------------------------- token freshness ----------------------------
 * Firebase recommends refreshing about monthly and says there is no benefit
 * beyond weekly. Weekly, on visit, with permission already granted, costs
 * nothing and keeps the server's staleness window (30 days) honest.       */
async function refreshIfDue() {
  if (!pushCapable || Notification.permission !== 'granted') return;
  if (Date.now() - Number(store.get('refreshed') || 0) < WEEK) return;
  try {
    const token = await mintToken();
    const previous = store.get('token');
    await post('/refresh', { token, previous: previous !== token ? previous : null });
    store.set('token', token);
    store.set('refreshed', String(Date.now()));
  } catch {}
}

/* ------------------------------- cart sync ------------------------------- */

async function cartToken() {
  try {
    const cart = await (await fetch('/cart.js', { headers: { Accept: 'application/json' } })).json();
    return cart.token?.split('?')[0] ?? null;     // normalised; see Phase 1 §4
  } catch { return null; }
}

// Themes mutate the cart through /cart/*.js endpoints. Watching fetch is the
// only hook that works across every theme without per-theme integration.
const nativeFetch = window.fetch;
window.fetch = async (...args) => {
  const res = await nativeFetch(...args);
  try {
    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url ?? '';
    if (res.ok && /\/cart\/add(\.js)?/.test(url)) queueMicrotask(() => ask('add_to_cart'));
    if (res.ok && /\/cart\/(add|change|update|clear)(\.js)?/.test(url)) syncCartSoon();
  } catch {}
  return res;
};

let syncTimer;
function syncCartSoon() {
  clearTimeout(syncTimer);
  syncTimer = setTimeout(async () => {
    const token = await cartToken();
    if (token) post('/cart', { cartToken: token }).catch(() => {});
  }, 800);
}

/* -------------------------- surface: notify me --------------------------- */
// Any element with data-engage-notify="<variantId>" becomes a size waitlist.
document.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-engage-notify]');
  if (!btn) return;
  e.preventDefault();
  await post('/notify-me', {
    variantId: btn.dataset.engageNotify,
    productHandle: btn.dataset.productHandle,
    sizeLabel: btn.dataset.sizeLabel
  });
  const token = await ask('notify_me');
  btn.textContent = token ? "We'll alert you" : 'Saved — we will message you';
  btn.disabled = true;
});

/* ------------------------------ foreground ------------------------------- */
// Pushes that arrive while the tab is focused skip the service worker's
// background handler. Show them in-page rather than as an OS notification.
async function foreground() {
  try {
    const { m, instance } = await messaging();
    m.onMessage(instance, ({ data = {} }) => showToast(data));
  } catch {}
}

/* ---------------------------------- boot --------------------------------- */
if (pushCapable) { refreshIfDue(); foreground(); }

// UI helpers (softAskDialog, showToast, showWhatsAppOptIn) are small DOM
// components in the same file; omitted here for length. The dialog must be
// dismissible with Esc, focus-trapped, and never block page interaction.
window.EngagePush = { ask };
```

**Why the fetch patch triggers `ask('add_to_cart')`.** Add-to-cart is the first moment of real intent on a fashion storefront. Asking on landing gets a reflexive "Block", and that decision is permanent for the origin. Asking immediately after an add-to-cart, with copy about price drops and back-in-stock, asks a question the shopper now has a reason to answer.

---

## 5. Service worker

Served by `ingest-api` at `/shopify/proxy/sw.js`, which the storefront sees as `/apps/push/sw.js`. Service workers load scripts with `importScripts`, so this file uses Firebase's **compat** builds. The Firebase config is injected at build time.

```js
// sw.js — built per environment; SW_VERSION and FIREBASE_CONFIG injected.
importScripts('https://www.gstatic.com/firebasejs/12.x.x/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/12.x.x/firebase-messaging-compat.js');

const SW_VERSION = '__SW_VERSION__';
firebase.initializeApp(__FIREBASE_CONFIG__);
const messaging = firebase.messaging();

const beacon = (kind, data) =>
  fetch('/apps/push/engagement', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ kind, sid: data.sid, sw: SW_VERSION })
  }).catch(() => {});

// Every push is data-only (Phase 3). That guarantees this handler runs and
// we control rendering; a `notification` block would bypass it.
messaging.onBackgroundMessage(({ data = {} }) => {
  const shown = self.registration.showNotification(data.title || 'BRAND', {
    body: data.body || '',
    icon: data.icon || '/apps/push/icon-192.png',
    badge: '/apps/push/badge-72.png',
    image: data.image || undefined,           // large image: Chrome on Android + desktop
    tag: data.tag || data.kind || 'engage',   // collapses repeats of the same intent
    renotify: false,
    requireInteraction: false,
    data: { url: data.url, sid: data.sid }
  });
  return Promise.all([shown, beacon('impression', data)]);
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const { url = '/', sid } = event.notification.data || {};
  event.waitUntil((async () => {
    beacon('click', { sid });
    const wins = await clients.matchAll({ type: 'window', includeUncontrolled: true });
    // Reuse an open store tab rather than stacking new ones.
    const tab = wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (tab) { await tab.navigate(url); return tab.focus(); }
    return clients.openWindow(url);
  })());
});

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(clients.claim()));
```

**Impressions and clicks.** FCM does not report per-message delivery for web push. The impression beacon is the closest thing to a delivery receipt available, and the click beacon is the success signal for push cascades (`CLAUDE.md` §3.3). Each push carries its `sid` (send id), so both beacons join straight back to the `sends` row.

**Icons.** `icon-192.png` and `badge-72.png` are served through the same proxy. Theme assets on `cdn.shopify.com` also work for images; only the worker script itself must be same-origin.

---

## 6. Opt-in capture on a non-Plus store

Three corrections to earlier assumptions shape this section:

1. **Meta requires opt-in before any message the business starts on WhatsApp**, including utility such as order updates. The opt-in must name the business and say the messages come on WhatsApp.
2. **Non-Plus stores cannot add fields to the checkout steps.** Only the Thank you and Order status pages accept extensions.
3. **Shopify's native "Text me with news and offers" is SMS consent.** It is a different channel with a different legal basis. Do not reuse it as WhatsApp consent.

So WhatsApp opt-in is captured at two points, before and after checkout.

### 6.1 Before checkout: cart attribute

A checkbox in the cart drawer and on the cart page writes a **cart attribute**. Shopify carries cart attributes into the order as `note_attributes`, so the opt-in rides on the order to `orders/create`. The phone number comes from checkout. The shopper never types their number twice.

```js
// Rendered by an app block in the cart drawer. Unticked by default.
async function setWhatsAppOptIn(checked) {
  await fetch('/cart/update.js', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ attributes: {
      _engage_wa_optin: checked ? 'yes' : '',
      _engage_wa_copy: checked ? CFG.copy.whatsappVersion : '',
      _engage_wa_at: checked ? new Date().toISOString() : ''
    }})
  });
  post('/prompt-event', { surface: 'cart', step: checked ? 'soft_accepted' : 'soft_dismissed' });
}
```

Attributes prefixed with `_` are hidden from the checkout display in most themes. On `orders/create`, the backend reads `note_attributes`, normalises the order phone, and grants `whatsapp` consent with the copy version and timestamp as evidence.

The attribute is set client-side, so it is untrusted in the sense that someone could set it by hand. The only effect is opting *their own order's phone* into messages from you. That risk is acceptable. The box is never pre-ticked. A pre-ticked box is not consent, and it is exactly the thing an auditor looks for.

**Capability discovery follows.** An order with this opt-in gets `order_confirmed_v1` on WhatsApp within a minute. That utility send is what tells you, for about twelve paise, whether the number has WhatsApp (`CLAUDE.md` §4.3).

### 6.2 After checkout: Thank you page extension

This target is available on every plan, and it catches everyone who skipped the cart checkbox. They have just paid and they want to know where their parcel is, so an offer of delivery updates on WhatsApp is at its most welcome here.

```jsx
// extensions/thankyou-whatsapp/src/ThankYou.jsx
// Target: purchase.thank-you.block.render
// Written against the Preact + Polaris web components API. Confirm component
// and API names against the checkout-ui-extensions version you pin.
import '@shopify/ui-extensions/preact';
import { render } from 'preact';
import { useState } from 'preact/hooks';

export default async () => render(<WhatsAppOptIn />, document.body);

function WhatsAppOptIn() {
  const [state, setState] = useState('idle');

  async function optIn() {
    setState('saving');
    const token = await shopify.sessionToken.get();          // JWT signed with the app secret
    const orderId = shopify.orderConfirmation.value.order.id;
    const res = await fetch('https://ingest.engage.brand.in/shopify/thankyou/whatsapp-optin', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderId, copyVersion: 'ty_wa_v1' })
    });
    setState(res.ok ? 'done' : 'error');
  }

  if (state === 'done') return <s-banner tone="success">Done — delivery updates will come on WhatsApp.</s-banner>;
  return (
    <s-section heading="Track this order on WhatsApp">
      <s-paragraph>Get dispatch, delivery and exchange updates from BRAND on WhatsApp.</s-paragraph>
      <s-button onClick={optIn} loading={state === 'saving'}>Yes, send updates on WhatsApp</s-button>
    </s-section>
  );
}
```

```toml
# extensions/thankyou-whatsapp/shopify.extension.toml
api_version = "2026-07"
[[extensions]]
type = "ui_extension"
name = "WhatsApp order updates"
handle = "thankyou-whatsapp"

[[extensions.targeting]]
module = "./src/ThankYou.jsx"
target = "purchase.thank-you.block.render"

[extensions.capabilities]
network_access = true
api_access = true
```

**Server side.** Verify the session token as an HS256 JWT signed with the app secret. Check `aud` equals the app's client id and `dest` equals the shop domain. Then attach consent to the **phone on the order**, never to a phone supplied in the request.

The Thank you page can render before `orders/create` arrives. Store the opt-in as pending, keyed by order id, and apply it when the webhook lands:

```java
@Post("/shopify/thankyou/whatsapp-optin")
public HttpResponse<?> optIn(@Header("Authorization") String auth, @Body ThankYouOptIn body) {
    var claims = sessionTokens.verify(auth.replace("Bearer ", ""));   // aud, dest, exp, nbf
    pendingOptIns.save(body.orderId(), claims.dest(), body.copyVersion(), clock.instant());
    orders.findByShopifyId(body.orderId())
          .ifPresent(order -> consent.applyPendingWhatsAppOptIn(order));  // webhook already here
    return HttpResponse.accepted();
}
```

The first WhatsApp message for these customers is `order_shipped_v1`. Shopify's own email has already confirmed the order. That shipping message performs the capability discovery.

### 6.3 In-thread opt-in

A "Chat with us on WhatsApp" link on the PDP and in the footer (`https://wa.me/91XXXXXXXXXX?text=...`) opens a user-initiated conversation, which opens the 24-hour service window. Inside it, send one quick-reply message: "Want delivery updates and size alerts here? [Yes] [No thanks]". A tap on Yes is an explicit, well-evidenced opt-in, recorded with the message id.

---

## 7. Web pixel for checkout timing

The checkout webhooks tell you **who** (phone, email) but not **when** someone stalled. The web pixel tells you when, down to the step, and it runs on the checkout without Plus.

```js
// extensions/engage-pixel/src/index.js
import { register } from '@shopify/web-pixels-extension';

register(({ analytics, settings }) => {
  const send = (name, event) =>
    fetch(settings.ingestUrl, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', 'X-Engage-Key': settings.writeKey },
      body: JSON.stringify({
        name,
        clientId: event.clientId,
        at: event.timestamp,
        checkoutToken: event.data?.checkout?.token ?? null,
        totalPaise: Math.round(Number(event.data?.checkout?.totalPrice?.amount ?? 0) * 100)
      })
    });

  for (const name of ['checkout_started', 'checkout_contact_info_submitted',
                      'checkout_shipping_info_submitted', 'payment_info_submitted',
                      'checkout_completed']) {
    analytics.subscribe(name, (e) => send(name, e));
  }
});
```

**The pixel deliberately sends no personal data.** Shopify redacts email, phone, name and address in pixel events for apps without approved protected customer data access. Even with access, the pixel is the wrong transport for PII: it runs in a sandbox and posts to a public endpoint. The pixel sends a **checkout token and a timestamp**. The `checkouts/update` webhook, which is HMAC-verified and server-to-server, supplies the phone for the same token.

The join: `pixel.checkoutToken` → `checkouts/update.token` (phone, `cart_token`) → `carts` → `devices` (via cart sync). That chain links a checkout stall to a push token and a WhatsApp number without PII ever passing through a browser-side script.

The pixel ingest endpoint is public. Authenticate it with a per-shop write key, rate-limit it per `clientId`, and treat its events as timing hints only. They never create or merge identities.

---

## 8. Server: proxy endpoints

```java
@Controller("/shopify/proxy")
@Secured(SecurityRule.IS_ANONYMOUS)
public class StorefrontProxyController {

    @Get(value = "/sw.js", produces = "application/javascript")
    public HttpResponse<String> serviceWorker() {
        // no-cache, not no-store: the browser must revalidate on every
        // update check, but may keep the file for offline wake-ups.
        return HttpResponse.ok(swBundle.content())
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .header("X-Engage-SW", swBundle.version());
    }

    @Post("/register")
    public HttpResponse<?> register(HttpRequest<?> req, @Valid @Body RegisterRequest body) {
        var ctx = proxyVerifier.verify(req);                          // Phase 1 §5
        rateLimiter.check("register", body.anonId(), 10, Duration.ofHours(1));
        copyVersions.requireKnown(Channel.PUSH, body.copyVersion());

        var identityId = ctx.customerId()
            .map(cid -> identity.resolveVerified(Key.shopifyCustomer(cid), Key.anon(body.anonId())))
            .orElseGet(() -> identity.resolveAnonymous(Key.anon(body.anonId())));

        var device = devices.upsert(identityId, body.token(), Platform.WEB,
                                    body.browser(), body.surface(), body.copyVersion());
        consent.grant(identityId, Channel.PUSH, Purpose.MARKETING, "soft_ask:" + body.surface(),
                      Evidence.of(body.copyVersion(), body.copyText(), body.page(), req));
        if (body.cartToken() != null) carts.attach(body.cartToken(), identityId);
        return HttpResponse.ok(Map.of("deviceId", device.id()));
    }

    // Remaining endpoints follow the same shape: verify → rate-limit → validate → act.
    //   POST /refresh       token rotation; deactivates `previous`
    //   POST /unregister    user turned notifications off in-page; withdraws push consent
    //   POST /cart          links the normalised cart token to the identity
    //   POST /notify-me     size-variant waitlist row
    //   POST /prompt-event  soft-ask funnel metrics
    //   POST /engagement    impression / click beacons from the service worker
}
```

**When an anonymous device later logs in**, the next proxied request carries `logged_in_customer_id`. That signed key lets the anonymous identity merge into the customer's identity (Phase 1 §2). This is the only merge path the storefront can trigger.

---

## 9. Token lifecycle

| Event | Action |
|---|---|
| Client refresh (weekly, on visit) | Update `last_refreshed_at`. If the token changed, insert the new one and deactivate the previous one with reason `rotated`. |
| Not refreshed for 30 days | Mark **stale**. Excluded from campaign audiences. Kept for back-in-stock, where the shopper explicitly asked. |
| FCM `UNREGISTERED` on send | Deactivate immediately, reason `unregistered`. |
| FCM `SENDER_ID_MISMATCH` | Deactivate. Usually a token minted against the staging project. |
| FCM `INVALID_ARGUMENT` | Deactivate **only** if the error names the token. Otherwise it is our payload's fault, so alert and do not prune. |
| User clicks "Turn off" in-page | `/unregister`. Deactivate, and withdraw push consent. |
| 180 days without refresh or click | Deactivate, reason `dormant`. |

Firebase's own guidance: refresh roughly monthly from the client, with no benefit beyond weekly, and treat about 30 days without a refresh as stale on the server.

---

## 10. Test matrix

| Platform | Browser | Expected |
|---|---|---|
| Android | Chrome, Samsung Internet, Edge | Soft-ask → native prompt → token → push with large image |
| Windows / macOS | Chrome, Edge | Same; images supported |
| Windows / macOS | Firefox | Token and push work; the `image` field is ignored, so test that the fallback looks right |
| macOS | Safari 16.4+ | Push works in the browser on macOS; permission needs a user gesture |
| iOS | Safari, Chrome (WebKit) | **No push ask shown.** WhatsApp opt-in shown instead; `ios_redirected_to_whatsapp` logged |
| Any | Incognito / private | Permission is denied or lost at session end; must not error |
| Any | Permission previously denied | No soft-ask shown; no console errors |

In-app browsers (Instagram, Facebook) matter in India because a large share of fashion traffic arrives from Instagram. They generally cannot hold a durable push subscription. Detect them (`FBAN`, `FBAV` and `Instagram` in the user agent), skip the push ask, and show the WhatsApp opt-in instead.

---

## 11. Metrics

```
engage_push_prompt_total{surface, step, platform}
engage_push_tokens{state="active|stale|inactive", browser}
engage_push_token_churn_total{reason}
```

The number to watch is **native-granted ÷ soft-shown, by surface**. Healthy targets for a fashion storefront: 8–15% on `notify_me` (high intent), 3–6% on `add_to_cart`, and 10–20% on the Thank you page. A surface below 2% has the wrong copy or the wrong moment. Fix the moment before rewriting the copy.

---

## Acceptance criteria

- [ ] `/apps/push/sw.js` registers with scope `/apps/push/` on the live theme; no console errors on Chrome, Edge, Firefox, Samsung Internet
- [ ] `getToken()` succeeds with the explicit registration; a Firebase test message from the console reaches the device while the tab is closed
- [ ] The native prompt is never shown without a preceding soft-ask acceptance on the same gesture
- [ ] iOS Safari and Instagram's in-app browser never show a push prompt; both show the WhatsApp opt-in
- [ ] A register request whose body claims another customer binds to the signed `logged_in_customer_id`
- [ ] A cart opt-in → order produces a WhatsApp consent row with copy version, timestamp and order id as evidence
- [ ] A Thank you page opt-in that arrives before `orders/create` is applied when the webhook lands
- [ ] Pixel `payment_info_submitted` for a checkout joins to that checkout's phone number via `checkouts/update`, with no PII sent by the pixel
- [ ] Weekly refresh rotates a changed token and deactivates the old one
- [ ] Prompt funnel dashboard shows soft-shown → granted → token by surface

## Exit gate

Live on production for 7 days. Tokens accumulating. Zero registration errors above 1%. The prompt funnel is measured per surface and nothing is estimated.

## Sources

- [Authenticate app proxies — Shopify](https://shopify.dev/docs/apps/build/online-store/app-proxies/authenticate-app-proxies)
- [Checkout extensibility by plan](https://www.fudge.ai/blog/shopify-checkout-extensibility/)
- [Web pixel checkout data and redaction](https://weltpixel.com/blogs/news/what-customer-data-is-available-in-shopify-web-pixel-events-and-what-shopify-redacts)
- [Get opt-in for WhatsApp — Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in)
- [Best practices for FCM registration token management — Firebase](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [iOS web push requirements](https://pushpad.xyz/blog/ios-special-requirements-for-web-push-notifications)
