// engage-push.js (P2-T04): platform routing for 8 user agents, the soft-ask
// sequence, and the WhatsApp cart attribute payload that ConsentWriter reads.
//
// The script decides the platform once, at load, so each test sets the
// browser up first and then imports a fresh copy.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const UA = {
  androidChrome: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36',
  desktopChrome: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36',
  firefox: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0',
  macSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15',
  iosSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
  instagram: 'Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.0.0 Mobile Safari/537.36 Instagram 350.0.0.30.103 Android',
  facebook: 'Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.0.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/480.0.0.35.108;]'
};

let calls;          // every fetch the script made: { url, body }
let requestPermission;

function define(obj, key, value) {
  Object.defineProperty(obj, key, { value, configurable: true, writable: true });
}

async function load({ ua, touch = 0, standalone = false, push = true, permission = 'default', storage = {},
                      session = {}, earlyAsk = { pageViews: 0, seconds: 0 } }) {
  vi.resetModules();
  document.body.innerHTML = '';
  localStorage.clear();
  sessionStorage.clear();
  for (const [k, v] of Object.entries(storage)) localStorage.setItem('engage:' + k, v);
  for (const [k, v] of Object.entries(session)) sessionStorage.setItem('engage:' + k, v);

  define(navigator, 'userAgent', ua);
  define(navigator, 'maxTouchPoints', touch);
  define(navigator, 'standalone', standalone || undefined);
  window.matchMedia = () => ({ matches: standalone });

  requestPermission = vi.fn(async () => 'denied');
  if (push) {
    define(navigator, 'serviceWorker', { register: vi.fn() });
    window.PushManager = function PushManager() {};
    window.Notification = { permission, requestPermission };
  } else {
    delete navigator.serviceWorker;
    delete window.PushManager;
    delete window.Notification;
  }

  calls = [];
  const mock = vi.fn(async (url, init = {}) => {
    calls.push({ url: String(url), body: init.body ? JSON.parse(init.body) : undefined });
    return { ok: true, status: 200, json: async () => ({ token: 'cart-1?key=x' }) };
  });
  window.fetch = mock;
  globalThis.fetch = mock;

  window.EngageConfig = {
    proxy: '/apps/push',
    earlyAsk,
    waNumber: '+91 98765 43210',
    copy: { push: 'Get size-back alerts', pushVersion: 'push_v1', whatsapp: 'Updates on WhatsApp', whatsappVersion: 'wa_v1' }
  };
  await import('../extensions/push-embed/assets/engage-push.js');
  return window.EngagePush;
}

const events = () => calls.filter((c) => c.url.endsWith('/prompt-event')).map((c) => c.body.step);
const tick = () => new Promise((r) => setTimeout(r, 0));

afterEach(() => { vi.useRealTimers(); });

/* --------------------------------- routing --------------------------------- */

describe('platform routing: soft ask or WhatsApp, decided before anything renders', () => {
  const softAsk = [
    ['Android Chrome', { ua: UA.androidChrome }, 'chrome', 'WEB'],
    ['desktop Chrome', { ua: UA.desktopChrome }, 'chrome', 'WEB'],
    ['Firefox', { ua: UA.firefox }, 'firefox', 'WEB'],
    ['macOS Safari', { ua: UA.macSafari }, 'safari', 'WEB'],
    ['iOS installed to the Home Screen', { ua: UA.iosSafari, standalone: true }, 'safari', 'IOS_WEB']
  ];
  it.each(softAsk)('%s gets our soft ask first', async (_name, env, browser, platform) => {
    const push = await load(env);
    push.ask('add_to_cart');
    await tick();

    expect(document.querySelector('.engage-overlay [role="dialog"]')).not.toBeNull();
    expect(document.querySelector('.engage-bar')).toBeNull();
    const shown = calls.find((c) => c.body?.step === 'soft_shown').body;
    expect(shown).toMatchObject({ surface: 'add_to_cart', browser, platform });
    expect(requestPermission).not.toHaveBeenCalled();
  });

  const whatsapp = [
    ['iOS Safari in a tab', { ua: UA.iosSafari }],
    ['iPadOS, which reports itself as a Mac', { ua: UA.macSafari, touch: 5 }],
    ['Instagram in-app browser', { ua: UA.instagram }],
    ['Facebook in-app browser', { ua: UA.facebook }]
  ];
  it.each(whatsapp)('%s never sees a push prompt; it is offered WhatsApp', async (_name, env) => {
    const push = await load(env);
    expect(await push.ask('add_to_cart')).toBeNull();

    expect(document.querySelector('.engage-overlay')).toBeNull();
    const link = document.querySelector('.engage-bar a');
    expect(link.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/919876543210\?text=/);
    expect(events()).toEqual(['ios_redirected_to_whatsapp']);
    expect(requestPermission).not.toHaveBeenCalled();
  });

  it('a browser without push support is not asked at all', async () => {
    const push = await load({ ua: UA.desktopChrome, push: false });
    expect(await push.ask('add_to_cart')).toBeNull();
    expect(document.body.children.length).toBe(0);
    expect(events()).toEqual([]);
  });
});

/* -------------------------------- soft ask -------------------------------- */

describe('soft ask', () => {
  it('the browser prompt comes only after "Yes", in the same click', async () => {
    const push = await load({ ua: UA.androidChrome });
    const result = push.ask('add_to_cart');
    await tick();
    expect(requestPermission).not.toHaveBeenCalled();

    document.querySelector('.engage-btn--primary').click();
    expect(await result).toBeNull();                       // the mock browser prompt answers "denied"
    expect(requestPermission).toHaveBeenCalledTimes(1);
    expect(events()).toEqual(['soft_shown', 'soft_accepted', 'native_denied']);
  });

  it('"Not now" is remembered for 14 days, so the next add-to-cart does not nag', async () => {
    const push = await load({ ua: UA.androidChrome });
    const first = push.ask('add_to_cart');
    await tick();
    [...document.querySelectorAll('.engage-btn')].find((b) => b.textContent === 'Not now').click();
    expect(await first).toBeNull();

    expect(await push.ask('add_to_cart')).toBeNull();
    expect(events()).toEqual(['soft_shown', 'soft_dismissed']);
    expect(requestPermission).not.toHaveBeenCalled();
  });

  it('asks again once the 14 days are over', async () => {
    const push = await load({ ua: UA.androidChrome, storage: { dismissed: String(Date.now() - 15 * 86400e3) } });
    push.ask('add_to_cart');
    await tick();
    expect(events()).toEqual(['soft_shown']);
  });

  it('a browser that blocked notifications is left alone', async () => {
    const push = await load({ ua: UA.androidChrome, permission: 'denied' });
    expect(await push.ask('add_to_cart')).toBeNull();
    expect(document.body.children.length).toBe(0);
    expect(events()).toEqual([]);
  });

  it('a successful add to cart is what opens the ask', async () => {
    await load({ ua: UA.androidChrome });
    await window.fetch('/cart/add.js', { method: 'POST' });
    await tick();
    await tick();
    expect(calls.find((c) => c.body?.step === 'soft_shown').body.surface).toBe('add_to_cart');
  });
});

/* ------------------------------- early ask -------------------------------- */

describe('early ask while browsing (most Indian stores ask on arrival; we ask softly, a little later)', () => {
  beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }); });

  const shownSurfaces = () => calls.filter((c) => c.body?.step === 'soft_shown').map((c) => c.body.surface);

  it('the second page view of a visit opens our soft ask, never the browser prompt', async () => {
    await load({ ua: UA.androidChrome, session: { pv: '1' }, earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(1500);

    expect(shownSurfaces()).toEqual(['browse']);
    expect(document.querySelector('.engage-overlay')).not.toBeNull();
    expect(requestPermission).not.toHaveBeenCalled();
  });

  it('on the first page it waits for the seconds on site', async () => {
    await load({ ua: UA.androidChrome, earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(19_000);
    expect(shownSurfaces()).toEqual([]);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(shownSurfaces()).toEqual(['browse']);
  });

  it('asks once per visit', async () => {
    await load({ ua: UA.androidChrome, session: { pv: '3', early: '1' }, earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(shownSurfaces()).toEqual([]);
  });

  it('respects "Not now" for 14 days and a blocked browser', async () => {
    await load({ ua: UA.androidChrome, session: { pv: '1' }, storage: { dismissed: String(Date.now()) },
                 earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(shownSurfaces()).toEqual([]);

    await load({ ua: UA.androidChrome, permission: 'denied', session: { pv: '1' }, earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(shownSurfaces()).toEqual([]);
  });

  it('0 turns a trigger off', async () => {
    await load({ ua: UA.androidChrome, session: { pv: '4' }, earlyAsk: { pageViews: 0, seconds: 0 } });
    await vi.advanceTimersByTimeAsync(120_000);
    expect(shownSurfaces()).toEqual([]);
  });

  it('never stacks a second prompt on an open one', async () => {
    const push = await load({ ua: UA.androidChrome, earlyAsk: { pageViews: 2, seconds: 20 } });
    push.ask('add_to_cart');                                // the add-to-cart ask is already open
    await vi.advanceTimersByTimeAsync(20_000);

    expect(shownSurfaces()).toEqual(['add_to_cart']);
    expect(document.querySelectorAll('.engage-overlay').length).toBe(1);
  });

  it('on iOS Safari the early moment offers WhatsApp instead', async () => {
    await load({ ua: UA.iosSafari, session: { pv: '1' }, earlyAsk: { pageViews: 2, seconds: 20 } });
    await vi.advanceTimersByTimeAsync(1500);
    expect(document.querySelector('.engage-bar a')).not.toBeNull();
    expect(events()).toEqual(['ios_redirected_to_whatsapp']);
  });
});

/* -------------------------- WhatsApp cart checkbox -------------------------- */

describe('WhatsApp cart opt-in: the attributes ConsentWriter reads from the order', () => {
  beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-30T06:30:00Z')); });

  it('ticking writes yes, the copy version and when it was ticked', async () => {
    const push = await load({ ua: UA.androidChrome });
    await push.setWhatsAppOptIn(true);

    const update = calls.find((c) => c.url === '/cart/update.js');
    expect(update.body).toEqual({ attributes: {
      _engage_wa_optin: 'yes',                   // ConsentWriter: n.na->>'_engage_wa_optin' = 'yes'
      _engage_wa_copy: 'wa_v1',
      _engage_wa_at: '2026-09-30T06:30:00.000Z'
    } });
    expect(events()).toEqual(['soft_accepted']);
  });

  it('unticking clears all three, so an earlier tick cannot count', async () => {
    const push = await load({ ua: UA.androidChrome });
    await push.setWhatsAppOptIn(false);

    const update = calls.find((c) => c.url === '/cart/update.js');
    expect(update.body).toEqual({ attributes: { _engage_wa_optin: '', _engage_wa_copy: '', _engage_wa_at: '' } });
    expect(events()).toEqual(['soft_dismissed']);
  });
});

/* -------------------------------- notify me -------------------------------- */

describe('notify me', () => {
  it('a sold-out size button joins the waitlist with its variant, handle and size', async () => {
    await load({ ua: UA.iosSafari });
    document.body.innerHTML = `<button data-engage-notify="44581230001" data-product-handle="linen-kurta"
                                       data-size-label="M">Notify me</button>`;
    const btn = document.querySelector('button');
    btn.click();
    await vi.waitFor(() => expect(btn.dataset.engageDone).toBe('1'));

    const notify = calls.find((c) => c.url === '/apps/push/notify-me');
    expect(notify.body).toMatchObject({ variantId: '44581230001', productHandle: 'linen-kurta', sizeLabel: 'M' });
    expect(notify.body.anonId).toMatch(/^[0-9a-f-]{36}$/);
    expect(btn.disabled).toBe(true);
  });
});
