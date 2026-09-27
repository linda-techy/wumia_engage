// engage-push.js — storefront push client (phase-2 §4). ES module.
// Firebase modular SDK loaded from gstatic; pin FIREBASE_VERSION and bump it
// deliberately (the service worker at /apps/push/sw.js must match).
const FIREBASE_VERSION = '12.19.0';
const CFG = window.EngageConfig || {};
const WEEK = 7 * 24 * 3600 * 1000;

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

const track = (surface, step) =>
  post('/prompt-event', { surface, step, platform: isIOS ? 'IOS_WEB' : 'WEB', browser: browserName() })
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
    const [{ initializeApp }, m] = await Promise.all([
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-app.js`),
      import(`https://www.gstatic.com/firebasejs/${FIREBASE_VERSION}/firebase-messaging.js`)
    ]);
    if (!(await m.isSupported())) throw new Error('fcm-unsupported');
    return { m, instance: m.getMessaging(initializeApp(CFG.firebase)) };
  })();
}

async function registration() {
  // The worker reads the public Firebase web config from its own URL, so the
  // theme editor stays the single place it is configured.
  const { apiKey, projectId, messagingSenderId, appId } = CFG.firebase;
  const query = new URLSearchParams({ apiKey, projectId, messagingSenderId, appId });
  const reg = await navigator.serviceWorker.register(`${CFG.proxy}/sw.js?${query}`, {
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
    if (isIOS || isInApp) { track(surface, 'ios_redirected_to_whatsapp'); showWhatsAppOptIn(surface); }
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
    await post('/refresh', { token, previous: previous && previous !== token ? previous : null });
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
  btn.textContent = token ? "We'll alert you" : 'Saved — we will message you';
  btn.disabled = true;
});

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

// Dismissible with Esc, focus-trapped, never blocks the page permanently.
function softAskDialog(text) {
  return new Promise((resolve) => {
    const done = (v) => { document.removeEventListener('keydown', onKey); overlay.remove(); resolve(v); };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    const yes = el('button', { class: 'engage-btn engage-btn--primary', onclick: () => done(true) }, 'Yes, notify me');
    const no = el('button', { class: 'engage-btn', onclick: () => done(false) }, 'Not now');
    const card = el('div', { class: 'engage-card', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Notifications' },
      el('p', { class: 'engage-card__text' }, text || 'Get an alert when your size is back or your bag price drops.'),
      el('div', { class: 'engage-card__actions' }, no, yes));
    const overlay = el('div', { class: 'engage-overlay', onclick: (e) => { if (e.target === overlay) done(false); } }, card);
    document.addEventListener('keydown', onKey);
    document.body.append(overlay);
    yes.focus();
  });
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
  const msg = encodeURIComponent('Yes, send me order and size-back-in-stock updates.');
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

// Public surface for theme code: onclick="EngagePush.ask('add_to_cart')",
// and a WhatsApp cart checkbox can call EngagePush.setWhatsAppOptIn(checked).
window.EngagePush = { ask, setWhatsAppOptIn, showWhatsAppOptIn };
