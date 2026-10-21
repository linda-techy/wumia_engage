// engage-push.js — storefront push client (phase-2 §4). ES module.
// Firebase modular SDK loaded from gstatic; pin FIREBASE_VERSION and bump it
// deliberately (the service worker at /apps/push/sw.js must match).
const FIREBASE_VERSION = '12.19.0';
const CFG = window.EngageConfig || {};
const WEEK = 7 * 24 * 3600 * 1000;
const DAY = 24 * 3600 * 1000;
const PROMPT = CFG.prompt || {};

// What Indian D2C sites do to earn the Allow: ask at a moment of intent, with a
// headline about that moment and a reason to say yes (a first-order offer,
// price-drop and back-in-stock alerts). The consent sentence under it is the
// registered copy (CFG.copy.push); the headline and offer are presentation.
const HEADLINES = {
  browse: PROMPT.headline || 'Be the first to know',
  add_to_cart: 'Want an alert if the price in your bag drops?',
  notify_me: "We'll tell you the moment it's back",
  product: 'Want a price-drop alert for this piece?',
  exit_intent: 'Before you go, stay in the loop',
  bell: PROMPT.headline || 'Be the first to know'
};

/* ------------------------------ environment ------------------------------ */

const ua = navigator.userAgent;
const isIOS = /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
const isStandalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
// In-app browsers (Instagram, Facebook) carry a large share of India fashion
// traffic and cannot hold a durable push subscription. Treat them like iOS:
// never show the push ask, offer WhatsApp instead.
const isInApp = /FBAN|FBAV|Instagram|Line\/|Twitter|GSA\//.test(ua);

// iOS Safari only supports web push for sites installed to the Home Screen,
// which almost no storefront visitor does. Showing a push ask there is a
// prompt that cannot work — WhatsApp is the iOS channel.
const pushCapable = 'serviceWorker' in navigator && 'PushManager' in window
                 && 'Notification' in window && !isInApp && (!isIOS || isStandalone);

const store = {
  get: (k) => { try { return localStorage.getItem('engage:' + k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem('engage:' + k, v); } catch {} }
};

// First-party anonymous id. Untrusted server-side; only stitches events from
// one browser before a verified identity exists.
const anonId = store.get('anon') || (() => {
  const id = crypto.randomUUID(); store.set('anon', id); return id;
})();

const post = (path, body) => fetch(`${CFG.proxy}${path}`, {
  method: 'POST', keepalive: true,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ anonId, ...body })
});

const track = (surface, step, reason) =>
  post('/prompt-event', { surface, step, platform: isIOS ? 'IOS_WEB' : 'WEB', browser: browserName(),
                          reason: reason ? String(reason).slice(0, 200) : undefined })
    .catch(() => {});

function browserName() {
  if (/SamsungBrowser/.test(ua)) return 'samsung';
  if (/Edg\//.test(ua)) return 'edge';
  if (/Firefox\//.test(ua)) return 'firefox';
  if (/Chrome\//.test(ua)) return 'chrome';
  if (/Safari\//.test(ua)) return 'safari';
  return 'other';
}

/* ------------------------------- firebase -------------------------------- */

let messagingP;
function messaging() {
  return messagingP ??= (async () => {
    const f = CFG.firebase || {};
    const missing = ['apiKey', 'projectId', 'messagingSenderId', 'appId'].filter((k) => !f[k]);
    if (missing.length || !CFG.vapidKey) {
      throw new Error('theme-settings-missing:' + missing.concat(CFG.vapidKey ? [] : ['vapidKey']).join(','));
    }
    const [{ initializeApp }, m] = await Promise.all([
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-app.js`),
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-messaging.js`)
    ]);
    if (!(await m.isSupported())) throw new Error('fcm-unsupported');
    return { m, instance: m.getMessaging(initializeApp(f)) };
  })().catch((e) => { messagingP = undefined; throw e; });   // a failure must not stick for the page's life
}

// The worker's scope is /apps/push/, so it never controls storefront pages and
// navigator.serviceWorker.ready (which waits for a worker controlling THIS
// page) would never resolve. Wait for this registration's own worker instead;
// getToken rejects while it is still installing.
function activated(reg) {
  const sw = reg.installing || reg.waiting || reg.active;
  if (!sw || sw.state === 'activated') return Promise.resolve(reg);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('sw-activation-timeout')), 15000);
    sw.addEventListener('statechange', () => {
      if (sw.state === 'activated') { clearTimeout(timer); resolve(reg); }
      if (sw.state === 'redundant') { clearTimeout(timer); reject(new Error('sw-redundant')); }
    });
  });
}

async function registration() {
  // The worker reads the public Firebase web config from its own URL, so the
  // theme editor stays the single place it is configured.
  const { apiKey, projectId, messagingSenderId, appId } = CFG.firebase;
  const query = new URLSearchParams({ apiKey, projectId, messagingSenderId, appId });
  const reg = await navigator.serviceWorker.register(`${CFG.proxy}/sw.js?${query}`, {
    scope: `${CFG.proxy}/`, updateViaCache: 'none'
  });
  return activated(reg);
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
export async function ask(surface, { userInitiated = false } = {}) {
  if (!pushCapable) {
    if (isIOS || isInApp) { track(surface, 'ios_redirected_to_whatsapp'); showWhatsAppOptIn(surface); }
    return null;
  }
  if (Notification.permission === 'denied') return null;
  if (Notification.permission === 'granted') return subscribe(surface);
  if (document.querySelector('.engage-ask:not(.engage-ask--out)')) return null;   // one prompt at a time

  // Do not nag: after "Later", wait reaskDays; at most maxAsks prompts ever.
  // A tap on the bell is the shopper asking, so neither limit applies.
  if (!userInitiated) {
    const dismissedAt = Number(store.get('dismissed') || 0);
    if (Date.now() - dismissedAt < Number(PROMPT.reaskDays ?? 7) * DAY) return null;
    if (Number(store.get('asks') || 0) >= Number(PROMPT.maxAsks ?? 3)) return null;
  }
  store.set('asks', String(Number(store.get('asks') || 0) + 1));
  session.set('asked', '1');
  hideBell();

  track(surface, 'soft_shown');
  const accepted = await softAskDialog(surface);
  if (!accepted) {
    store.set('dismissed', String(Date.now()));
    track(surface, 'soft_dismissed');
    showBell();
    return null;
  }
  track(surface, 'soft_accepted');

  const result = await Notification.requestPermission();      // still within the click gesture
  track(surface, result === 'granted' ? 'native_granted'
               : result === 'denied' ? 'native_denied' : 'native_dismissed');
  if (result !== 'granted') { if (result === 'default') showBell(); return null; }
  const token = await subscribe(surface);
  if (token) showWelcome();
  return token;
}

async function subscribe(surface) {
  try {
    const token = await mintToken();
    const res = await post('/register', {
      token, surface, browser: browserName(),
      platform: isIOS ? 'IOS_WEB' : 'WEB',   // iOS only reaches here as an installed PWA
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
    track(surface, 'token_failed', e && (e.code || e.message));
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
    const res = await post('/refresh', { token, previous: previous && previous !== token ? previous : null });
    // 202: the server holds no device for this browser (never registered, or
    // not storable). Do not record a refresh, so the next ask() registers it.
    if (res.status !== 200) { store.set('token', ''); return; }
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

/* ------------------------- surface: WhatsApp opt-in ---------------------- */

async function setWhatsAppOptIn(checked) {
  try {
    await fetch('/cart/update.js', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ attributes: {
        _engage_wa_optin: checked ? 'yes' : '',
        _engage_wa_copy: checked ? CFG.copy.whatsappVersion : '',
        _engage_wa_at: checked ? new Date().toISOString() : ''
      }})
    });
  } catch {}
  post('/prompt-event', { surface: 'cart', step: checked ? 'soft_accepted' : 'soft_dismissed' }).catch(() => {});
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
  }).catch(() => {});
  const token = await ask('notify_me');
  btn.dataset.engageDone = '1';     // the notify-me block must not re-label it
  btn.textContent = token ? "We'll alert you" : 'Saved — we will message you';
  btn.disabled = true;
});

const session = {
  get: (k) => { try { return sessionStorage.getItem('engage:' + k); } catch { return null; } },
  set: (k, v) => { try { sessionStorage.setItem('engage:' + k, v); } catch {} }
};

/* ---------------------- surface: early (browsing) ----------------------- */
// Most Indian stores ask as soon as someone lands. We ask a little later and
// softly: on the Nth page view of a visit, or after N seconds on the site,
// whichever comes first, once per visit. Our own prompt comes first; the
// browser's Allow only after a tap, so a "Block" is never spent on arrival.
// ask() still honours a blocked browser and a "Not now" within 14 days.
function earlyAsk() {
  const cfg = CFG.earlyAsk || {};
  const views = Number(cfg.pageViews ?? 2);
  const seconds = Number(cfg.seconds ?? 20);
  if (session.get('early') || session.get('asked')) return;   // already asked this visit
  const count = Number(session.get('pv') || 0) + 1;
  session.set('pv', String(count));

  let timer;
  const fire = () => {
    clearTimeout(timer);
    if (session.get('early')) return;
    session.set('early', '1');
    ask('browse');
  };
  if (views > 0 && count >= views) { timer = setTimeout(fire, 1500); return; }   // let the page settle
  if (seconds > 0) timer = setTimeout(fire, seconds * 1000);
}

/* ----------------------- surface: product interest ----------------------- */
// A second look at the same piece, or a long look, is price-drop intent:
// ask with a headline about that piece rather than a generic one.
function productAsk() {
  const m = location.pathname.match(/\/products\/([^/?#]+)/);
  if (!m || session.get('asked')) return;
  let seen = [];
  try { seen = JSON.parse(store.get('seen') || '[]'); } catch {}
  const again = seen.includes(m[1]);
  store.set('seen', JSON.stringify([m[1], ...seen.filter((h) => h !== m[1])].slice(0, 30)));
  const seconds = Number(PROMPT.productSeconds ?? 25);
  const fire = () => { if (!session.get('asked')) ask('product'); };
  if (again) setTimeout(fire, 4000);
  else if (seconds > 0) setTimeout(fire, seconds * 1000);
}

/* ------------------------- surface: exit intent -------------------------- */
// Desktop only (a pointer leaving through the top of the window): the last
// chance in this visit, once per visit, and never if we already asked.
function exitIntent() {
  if (PROMPT.exitIntent === false || matchMedia('(pointer: coarse)').matches) return;
  const onOut = (e) => {
    if (e.relatedTarget || e.clientY > 0) return;
    document.removeEventListener('mouseout', onOut);
    if (!session.get('asked')) ask('exit_intent');
  };
  document.addEventListener('mouseout', onOut);
}

/* ------------------------------ the bell --------------------------------- */
// After "Later", a small bell stays in the corner, so someone who changes
// their mind can subscribe with one tap, without being asked again.
function showBell() {
  if (PROMPT.bell === false || !pushCapable || Notification.permission !== 'default') return;
  if (document.querySelector('.engage-bell')) return;
  document.body.append(el('button', { class: 'engage-bell', type: 'button', 'aria-label': 'Get alerts from ' + (PROMPT.brand || 'us'),
    onclick: () => ask('bell', { userInitiated: true }) }, bellIcon()));
}
function hideBell() { document.querySelector('.engage-bell')?.remove(); }
function bellIcon() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('width', '22'); svg.setAttribute('height', '22');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('fill', 'currentColor');
  path.setAttribute('d', 'M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm7-6V11a7 7 0 0 0-5.5-6.84V3.5a1.5 1.5 0 0 0-3 0v.66A7 7 0 0 0 5 11v5l-2 2v1h18v-1l-2-2Z');
  svg.append(path);
  return svg;
}

/* ------------------------------ foreground ------------------------------- */
// Pushes that arrive while the tab is focused skip the SW background handler.
async function foreground() {
  try {
    const { m, instance } = await messaging();
    m.onMessage(instance, ({ data = {} }) => showToast(data));
  } catch {}
}

/* -------------------------------- UI bits -------------------------------- */

function el(tag, attrs = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') n.className = v;
    else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v);
  }
  for (const kid of kids) n.append(kid);
  return n;
}

// The soft ask: a branded card that slides in (bottom sheet on phones, top
// corner on desktop) without darkening or blocking the page, like the native
// prompt it stands in for. Esc or "Later" dismisses it.
function softAskDialog(surface) {
  return new Promise((resolve) => {
    const done = (v) => {
      document.removeEventListener('keydown', onKey);
      card.classList.remove('engage-ask--in');
      card.classList.add('engage-ask--out');         // gone for ask(), still fading out
      setTimeout(() => card.remove(), 200);
      resolve(v);
    };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    const yes = el('button', { class: 'engage-btn engage-btn--primary', type: 'button', onclick: () => done(true) }, 'Allow');
    const no = el('button', { class: 'engage-btn engage-btn--quiet', type: 'button', onclick: () => done(false) }, 'Later');
    const headline = HEADLINES[surface] || HEADLINES.browse;
    const card = el('div', { class: 'engage-ask', role: 'dialog', 'aria-label': headline },
      el('div', { class: 'engage-ask__head' },
        PROMPT.logo ? el('img', { class: 'engage-ask__logo', src: PROMPT.logo, alt: '' }) : '',
        el('strong', { class: 'engage-ask__title' }, headline)),
      el('p', { class: 'engage-ask__text' }, CFG.copy.push || 'Get order updates, and an alert when your size is back or your bag price drops.'),
      // An offer line only with a code to give: never promise what the welcome cannot deliver.
      PROMPT.offerText && (PROMPT.offerCode || '').trim() ? el('p', { class: 'engage-ask__offer' }, PROMPT.offerText) : '',
      el('div', { class: 'engage-ask__actions' }, no, yes));
    document.addEventListener('keydown', onKey);
    document.body.append(card);
    setTimeout(() => card.classList.add('engage-ask--in'), 30);
    yes.focus({ preventScroll: true });
  });
}

// The reward for saying yes (reciprocity): the welcome code, revealed only
// once the browser's Allow went through, with a one-tap copy.
function showWelcome() {
  const code = (PROMPT.offerCode || '').trim();
  const copy = code ? el('button', { class: 'engage-btn engage-btn--primary', type: 'button', onclick: async () => {
    try { await navigator.clipboard.writeText(code); copy.textContent = 'Copied ' + code; } catch {}
  } }, 'Copy ' + code) : '';
  const box = el('div', { class: 'engage-toast engage-welcome', role: 'status' },
    el('div', { class: 'engage-toast__body' },
      el('strong', {}, "You're in!"),
      el('span', {}, code ? 'Use this code on your first order.' : "We'll send you new drops, alerts and order updates.")),
    copy);
  document.body.append(box);
  setTimeout(() => box.classList.add('engage-toast--in'), 30);
  setTimeout(() => box.remove(), code ? 20000 : 6000);
}

function showToast(data = {}) {
  const toast = el('a', { class: 'engage-toast', href: data.url || '#' },
    data.image ? el('img', { class: 'engage-toast__img', src: data.image, alt: '' }) : '',
    el('div', { class: 'engage-toast__body' },
      el('strong', {}, data.title || 'Wumika'),
      el('span', {}, data.body || '')));
  document.body.append(toast);
  setTimeout(() => toast.classList.add('engage-toast--in'), 30);
  setTimeout(() => toast.remove(), 8000);
}

// iOS / in-app fallback: a wa.me link opens a user-initiated WhatsApp thread,
// which is the compliant opt-in path there (phase-2 §6.3).
function showWhatsAppOptIn(surface) {
  const num = (CFG.waNumber || '').replace(/[^0-9]/g, '');
  if (!num) return;
  if (store.get('wa-dismissed')) return;
  // wa_inthread_v1 (V14): the shopper sends this themselves, which is the opt-in.
  const msg = encodeURIComponent('Yes, send me order updates, size-back-in-stock alerts and offers on WhatsApp.');
  const link = el('a', { class: 'engage-btn engage-btn--primary', href: `https://wa.me/${num}?text=${msg}`,
    target: '_blank', rel: 'noopener', onclick: () => { track(surface, 'soft_accepted'); bar.remove(); } },
    'Get updates on WhatsApp');
  const close = el('button', { class: 'engage-bar__close', 'aria-label': 'Dismiss',
    onclick: () => { store.set('wa-dismissed', '1'); bar.remove(); } }, '×');
  const bar = el('div', { class: 'engage-bar' },
    el('span', {}, CFG.copy.whatsapp || 'Get order and delivery updates on WhatsApp'), link, close);
  document.body.append(bar);
}

/* ---------------------------------- boot --------------------------------- */
if (pushCapable) { refreshIfDue(); foreground(); }
earlyAsk();
productAsk();
exitIntent();
// Someone who said "Later" before gets the bell from the first page, not the card.
if (store.get('dismissed')) showBell();

// Public surface for theme code: onclick="EngagePush.ask('add_to_cart')",
// and a WhatsApp cart checkbox can call EngagePush.setWhatsAppOptIn(checked).
window.EngagePush = { ask, setWhatsAppOptIn, showWhatsAppOptIn };
